## DNS Single Proxy - Java 구현 스펙

### 1. 프로젝트 개요
| 항목 | 내용 |
|-----|------|
| 목적 | __check_pf() 호출 회피를 위해 DNS 응답을 타입당 1개로 제한 |
| 대체 대상 | systemd-resolved |
| 호환성 | /etc/systemd/resolved.conf 설정 파일 재사용 |

### 2. 핵심 기능
[필수]
- UDP/TCP DNS 서버 (포트 53)
- Upstream DNS 쿼리 및 응답 필터링
- 응답 레코드를 타입(A, AAAA, CNAME 등)당 1개로 제한
- TTL 기반 캐시
- resolved.conf 파싱
  [선택]
- DNS over TLS (DoT)
- 헬스체크/메트릭

### 3. 설정 파일 스펙

#### 3.1 파싱 대상
1. /etc/systemd/resolved.conf
2. /etc/systemd/resolved.conf.d/*.conf (알파벳 순, 나중 값이 override)

#### 3.2 지원 항목
| 키 | 타입 | 기본값 | 설명 |
|----|-----|-------|------|
| DNS | List<String> | 8.8.8.8 | 주 DNS 서버 (공백 구분) |
| FallbackDNS | List<String> | 1.1.1.1 | 폴백 DNS 서버 |
| Cache | boolean | true | 캐시 활성화 (yes/no) |
| DNSStubListener | boolean | true | 127.0.0.53:53 리스닝 |
| DNSStubListenerExtra | List<String> | [] | 추가 리스닝 주소 |

#### 3.3 파싱 규칙
- [Resolve] 섹션만 처리
- '#', ';' 로 시작하는 줄은 주석
- 빈 줄 무시
- key=value 형식 (공백 trim)
- 알 수 없는 키는 무시 (경고 로그)
- **DNS, FallbackDNS, DNSStubListenerExtra는 누적 방식**
  - 같은 키를 여러 줄로 작성하면 모두 누적됨
  - 공백으로 구분된 여러 값도 지원

#### 3.4 DNS 설정 예시
```ini
[Resolve]
# 방법 1: 공백으로 구분
DNS=1.1.1.1 8.8.8.8 9.9.9.9

# 방법 2: 여러 줄로 작성 (누적됨)
DNS=1.1.1.1
DNS=8.8.8.8
DNS=9.9.9.9

# 방법 3: 혼합 사용 가능
DNS=1.1.1.1 8.8.8.8
DNS=9.9.9.9
# 결과: [1.1.1.1, 8.8.8.8, 9.9.9.9]

FallbackDNS=1.0.0.1
FallbackDNS=8.8.4.4
# 결과: [1.0.0.1, 8.8.4.4]
```

### 4. DNS 프로토콜 스펙

#### 4.1 서버
| 항목 | 값 |
|-----|-----|
| 프로토콜 | UDP (필수), TCP (필수) |
| 기본 바인드 | 127.0.0.53:53 |
| 메시지 크기 | UDP 512 bytes (EDNS 미지원 시), TCP 65535 bytes |

#### 4.2 클라이언트 (Upstream 쿼리)
| 항목 | 값 |
|-----|-----|
| 타임아웃 | 2초 |
| 재시도 | 주 DNS 전체 시도 → FallbackDNS 전체 시도 |
| 포트 | 53 |

#### 4.2.1 여러 DNS 서버 동작 방식
```
DNS 쿼리 요청
    │
    ▼
┌─────────────────────────────────────┐
│ Primary DNS 서버 순차 시도           │
│ (DNS= 에 설정된 순서대로)            │
│                                     │
│  DNS[0] → 실패 → DNS[1] → 실패 → ... │
└─────────────────────────────────────┘
    │ 모두 실패
    ▼
┌─────────────────────────────────────┐
│ Fallback DNS 서버 순차 시도          │
│ (FallbackDNS= 에 설정된 순서대로)     │
│                                     │
│  Fallback[0] → 실패 → Fallback[1]...│
└─────────────────────────────────────┘
    │ 모두 실패
    ▼
  SERVFAIL 반환
```

**동작 규칙:**
- 각 서버에 2초 타임아웃 적용
- 첫 번째 성공 응답 즉시 반환 (나머지 서버 시도 안 함)
- UDP 응답이 truncated면 같은 서버에 TCP로 재시도
- Primary 전체 실패 시에만 Fallback 시도

#### 4.3 응답 필터링 로직
```java
// Pseudocode
Map<Integer, ResourceRecord> seen = new HashMap<>();
List<ResourceRecord> filtered = new ArrayList<>();
for (ResourceRecord rr : response.getAnswers()) {
    int type = rr.getType();  // A=1, AAAA=28, CNAME=5, ...
    if (!seen.containsKey(type)) {
        seen.put(type, rr);
        filtered.add(rr);
    }
}
response.setAnswers(filtered);
```

### 5. 캐시 스펙
| 항목 | 값 |
|-----|-----|
| 키 | {qname}:{qtype}:{qclass} |
| TTL | 응답의 최소 TTL 사용 |
| 최대 엔트리 | 10,000 (설정 가능) |
| Eviction | TTL 만료 또는 LRU |
| 네거티브 캐시 | NXDOMAIN 30초 |

### 6. 클래스 구조
```
com.logpresso.dnsproxy
├── Main.java                    # 엔트리포인트
├── config/
│   ├── ResolvedConfig.java      # 설정 모델
│   └── ResolvedConfigParser.java
├── server/
│   ├── DnsServer.java           # UDP/TCP 서버
│   └── DnsHandler.java          # 요청 처리
├── client/
│   └── UpstreamResolver.java    # Upstream 쿼리
├── filter/
│   └── SingleRecordFilter.java  # 타입당 1개 필터
└── cache/
└── DnsCache.java            # TTL 캐시
```

### 7. 의존성
```xml
<!-- DNS 메시지 파싱 -->
<dependency>
    <groupId>dnsjava</groupId>
    <artifactId>dnsjava</artifactId>
    <version>3.5.3</version>
</dependency>
```
또는 직접 파싱 (외부 의존성 제거 시)

### 8. 실행/배포

#### 8.1 빌드
```bash
mvn clean package -DskipTests
# 결과: target/dns-single-proxy.jar
```

#### 8.2 실행
```bash
java -jar dns-single-proxy.jar
# 또는 설정 경로 지정
java -jar dns-single-proxy.jar --config /etc/systemd/resolved.conf
```

#### 8.3 systemd 서비스 설치/제거

##### 설치 (--install)
systemd-resolved를 대체하여 시스템 DNS 서비스로 설치합니다.

```bash
# 빌드 후 설치 (root 권한 필요)
mvn clean package -DskipTests
sudo java -jar target/dns-single-proxy.jar --install
```

**설치 과정:**
1. systemd-resolved 서비스 중지 및 비활성화
2. `/opt/dns-single-proxy/` 디렉토리 생성
3. JAR 파일 복사
4. systemd 서비스 파일 생성 (`/etc/systemd/system/dns-single-proxy.service`)
5. 서비스 활성화 및 시작

**설치 후 확인:**
```bash
systemctl status dns-single-proxy
journalctl -u dns-single-proxy -f
```

##### 제거 (--uninstall)
dns-single-proxy를 제거하고 systemd-resolved를 복원합니다.

```bash
sudo java -jar /opt/dns-single-proxy/dns-single-proxy.jar --uninstall
```

**제거 과정:**
1. dns-single-proxy 서비스 중지 및 비활성화
2. 서비스 파일 삭제
3. `/opt/dns-single-proxy/` 디렉토리 삭제
4. systemd-resolved 재활성화 및 시작

#### 8.4 수동 Systemd 서비스 설정 (참고용)
`--install` 옵션이 자동으로 생성하는 서비스 파일 내용:
```ini
[Unit]
Description=DNS Single Proxy
Documentation=https://github.com/logpresso/dns-single-proxy
After=network.target
Before=nss-lookup.target
Wants=nss-lookup.target

[Service]
Type=simple
ExecStart=/usr/bin/java -jar /opt/dns-single-proxy/dns-single-proxy.jar
Restart=always
RestartSec=5
AmbientCapabilities=CAP_NET_BIND_SERVICE

[Install]
WantedBy=multi-user.target
```

### 9. 로깅
```
INFO  - 서버 시작/종료
INFO  - 설정 로드 완료
WARN  - Upstream 쿼리 실패, 폴백 사용
DEBUG - 개별 쿼리/응답 (기본 off)
ERROR - 바인드 실패, 설정 파싱 오류
```

### 10. 테스트 시나리오
| 케이스 | 검증 |
|-------|------|
| A 레코드 여러 개 응답 | 1개만 반환 |
| A + AAAA 혼합 | 각 1개씩 반환 |
| CNAME 체인 | CNAME 1개 + A 1개 |
| NXDOMAIN | 그대로 전달, 캐시 |
| Upstream 전체 실패 | SERVFAIL 반환 |
| 캐시 히트 | Upstream 쿼리 없음 |
