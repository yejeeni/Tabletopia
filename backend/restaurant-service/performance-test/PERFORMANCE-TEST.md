# 레스토랑 예약 시스템 Redis 성능 테스트

## 개요

Redis 캐싱을 적용한 예약 시스템의 성능 개선 효과를 Apache JMeter로 측정한 부하 테스트입니다.

---

## 테스트 시나리오

### 1. 타임슬롯 조회 성능 테스트
- **목적**: Redis 캐싱 vs DB 직접 조회 성능 비교
- **시나리오**:
  - Before: Redis 캐싱 비활성화 (DB만 사용)
  - After: Redis 캐싱 활성화
- **측정 지표**: 평균 응답 시간, 95th Percentile, TPS, 에러율

### 2. 테이블 선점 동시성 테스트
- **목적**: Redis SETNX 원자적 연산의 동시성 제어 검증
- **시나리오**: 100명이 동시에 같은 테이블 선점 시도
- **예상 결과**: 정확히 1명만 성공, 99명 실패

---

## 실행 방법

### 필요 조건

```bash
# JMeter 설치
choco install jmeter

# Redis 실행 (Docker)
docker run -d -p 6379:6379 redis:latest

# Spring Boot 실행
./gradlew bootRun
```

### 테스트 실행

```bash
# GUI 모드 (시각적 확인용)
jmeter -t performance-test/reservation-load-test.jmx

# CLI 모드 (실제 부하 테스트용)
jmeter -n -t performance-test/reservation-load-test.jmx \
  -l performance-test/results/test-results.jtl \
  -e -o performance-test/results/html-report

# 간편 스크립트
.\performance-test\run-test.bat   # Windows
./performance-test/run-test.sh    # Linux/Mac
```

### 결과 확인

```bash
# HTML 리포트
performance-test/results/html-report/index.html

# 애플리케이션 성능 로그
grep "[성능측정]" logs/application.log
```

---

## 측정 결과

### 1. 로컬 DB 환경

| 항목 | 내용 |
|---|---|
| OS | Windows 11 |
| Spring Boot | 3.5.6 |
| DB | MySQL 8.0 (로컬, localhost:3306) |
| Redis | Docker (localhost:6379) |
| 테스트 대상 | `GET /api/user/restaurants/1/timeslots?date=2026-04-15` |
| 테스트 규모 | 총 3,500건, 100명 동시 사용자 |

**Before — 캐시 없음**
```
summary =  3500 in 00:01:05 =  53.5/s  Avg:  6ms  Min:  3ms  Max: 163ms  Err:   0 (0.00%)
```

**After — Redis 캐시 적용**
```
summary =  3500 in 00:01:06 =  53.2/s  Avg: 20ms  Min:  4ms  Max: 1379ms  Err:   0 (0.00%)
```

> 로컬 DB가 이미 6ms로 빠르기 때문에 Redis 캐시의 속도 개선 효과가 미미함.
> 캐시 워밍업 이후 응답 시간 8~9ms로 Before와 유사. → 원격 DB 환경에서 재측정.

---

### 2. 원격 DB 환경 (Oracle Cloud VM + MySQL)

| 항목 | 내용 |
|---|---|
| OS | Windows 11 |
| Spring Boot | 3.5.6 |
| DB | MySQL 8.0 (Oracle Cloud VM, 원격) |
| Redis | Docker (localhost:6379) |
| 테스트 대상 | `GET /api/user/restaurants/1/timeslots?date=2026-04-15` |
| 테스트 규모 | 총 3,500건, 100명 동시 사용자 |

**Before — 캐시 없음**
```
summary +  1353 in 00:00:12 = 114.4/s  Avg: 134ms  Min:   0ms  Max: 1516ms  Err: 554 (40.95%)
summary +  1251 in 00:00:30 =  41.8/s  Avg:  65ms  Min:  40ms  Max:  656ms  Err:   0 (0.00%)
summary +   896 in 00:00:24 =  37.7/s  Avg:  61ms  Min:  40ms  Max:  383ms  Err:   0 (0.00%)
summary =  3500 in 00:01:06 =  53.4/s  Avg:  91ms  Min:   0ms  Max: 1516ms  Err: 554 (15.83%)
```

**After — Redis 캐시 적용**
```
summary +  1592 in 00:00:18 =  90.6/s  Avg: 186ms  Min:  40ms  Max: 2469ms  Err:   0 (0.00%)
summary +  1253 in 00:00:30 =  41.9/s  Avg:  53ms  Min:  39ms  Max:  484ms  Err:   0 (0.00%)
summary +   655 in 00:00:18 =  36.7/s  Avg:  51ms  Min:  40ms  Max:   98ms  Err:   0 (0.00%)
summary =  3500 in 00:01:05 =  53.6/s  Avg: 113ms  Min:  39ms  Max: 2469ms  Err:   0 (0.00%)
```

---

### 3. 핵심 비교 (원격 DB 기준)

| 항목 | Before (캐시 없음) | After (Redis 캐시) | 개선 |
|---|---|---|---|
| 전체 평균 응답 | 91ms | 113ms | - |
| **안정 구간 평균** | **61~65ms** | **51~53ms** | **▼ 22% 단축** |
| 에러율 | **15.83%** | **0%** | **▼ 완전 제거** |
| 에러 건수 | 554건 | 0건 | **554건 제거** |
| TPS | 53.4/s | 53.6/s | 유사 |

> **After의 전체 평균이 높은 이유**: 캐시 워밍업 전 1번째 배치에서 캐시 미스로 DB 요청이 집중되어 186ms 소요.
> 캐시가 채워진 이후(2~3번째 배치)부터는 51~53ms로 Before보다 빠름.

---

## 분석

### 에러율 개선이 핵심

Before에서 발생한 554건 에러의 원인은 **원격 DB 커넥션 풀 초과**.
100명 동시 요청 시 DB 커넥션이 부족해 타임아웃 발생.

Redis 캐시 적용 후 동일 레스토랑/날짜 요청이 Redis에서 반환되어 DB 요청이 줄어들었고,
커넥션 풀 압박이 해소되어 에러가 완전히 사라짐.

### 속도 개선 (캐시 안정화 이후)

| 구간 | Before | After |
|---|---|---|
| 안정 구간 응답 | 61~65ms | 51~53ms |
| 개선폭 | - | 약 22% 단축 |

---

## Redis 활용 현황

### 1. 테이블 선점 동시성 제어 (기존 코드)

```
목적: Race Condition 없이 정확히 1명만 테이블 선점에 성공하도록 제어
방법: Redis SETNX (SET if Not eXists) 원자적 연산
TTL: 5분 (결제 시간 고려)
```

| 메서드 | 역할 |
|---|---|
| `trySelectTable()` | SETNX 원자적 선점 |
| `saveTableSelection()` | 선점 정보 저장 |
| `getTableSelection()` | 선점 정보 조회 |
| `cacheReservationStatus()` | 예약 확정 상태 캐싱 |
| `getCachedReservationStatus()` | 예약 상태 캐시 조회 |
| `addConnectedUser()` | WebSocket 접속자 추적 |

### 2. 타임슬롯 조회 읽기 캐시

```
목적: 동일 레스토랑/날짜 반복 조회 시 DB 부하 감소
방법: 첫 조회 결과를 Redis에 5분 TTL로 저장
효과: 에러율 15.83% → 0%, 안정 구간 응답 22% 단축
```

---

## 트러블슈팅

### Connection Timeout 발생
```yaml
spring:
  redis:
    timeout: 3000ms
    lettuce:
      pool:
        max-active: 100
        max-idle: 50
```

### JMeter Out of Memory 에러
```bash
export HEAP="-Xms1g -Xmx4g"
jmeter -n -t test.jmx
```

### Redis 연결 실패
```bash
redis-cli ping
docker restart <redis-container-id>
```
