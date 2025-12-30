## 1. PayoutMember 생성 후 이벤트(PayoutMemberCreatedEvent) 발생, 주문 결제가 완료되면 이벤트(MarketOrderPaymentCompletedEvent) 발생

[0041](https://github.com/jhs512/p-14116-1/commit/0041#diff-1f624a1010be8cac3e8e25331ba57d6badebd1ee30c4262866101a871cfa8489)

```plain text
[Member BC] MemberJoinedEvent 발행
    ↓
PayoutEventListener.handle(MemberJoinedEvent)
    ↓
PayoutFacade.syncMember()
    ↓
PayoutSyncMemberUseCase.syncMember()
    ↓
PayoutMemberRepository.save() → PayoutMember 저장
    ↓
(신규면) PayoutMemberCreatedEvent 발행
    ↓
PayoutEventListener.handle(PayoutMemberCreatedEvent)
    ↓
PayoutFacade.createPayout()
    ↓
PayoutCreatePayoutUseCase.createPayout()
```

---

## 2. PayoutMemberCreatedEvent 이벤트 수신 후 Payout 생성

[0042](https://github.com/jhs512/p-14116-1/commit/0042)

- PayoutCreatePayoutUseCase 내에서 PayoutMember에 대한 Payout 생성

---

## 3. MarketOrderPaymentCompletedEvent 이벤트 수신 후 주문 품목 불러오기

[0043](https://github.com/jhs512/p-14116-1/commit/0043)

```plain text
[이벤트 발행]
MarketOrderPaymentCompletedEvent
        ↓
[Payout 도메인 - 이벤트 리스너]
PayoutAddPayoutCandidateItemsUseCase.addPayoutCandidateItems()
        ↓
[Shared 모듈 - HTTP 클라이언트]
MarketApiClient.getOrderItems(orderId)
        ↓ HTTP GET 요청
[Market 도메인 - REST API]
ApiV1OrderController.getItems()
        ↓
[Market 도메인 - 비즈니스 로직]
marketFacade.findOrderById() → Order.getItems()
        ↓
[Market 도메인 - 엔티티]
OrderItem.toDto() → OrderItemDto 반환
        ↓ HTTP 응답
[Payout 도메인]
List<OrderItemDto> 수신 완료
```
---

## 4. 주문 품목 불러오기 후 PayoutCandidateItem 생성

[0044](https://github.com/jhs512/p-14116-1/commit/0044)

### PayoutAddPayoutCandidateItemsUseCase.java에서 makePayoutCandidateItems 메서드가 2번 있는 이유
- 주문 상품 1개에서 “수수료 정산”과 “판매 대금 정산”이라는 서로 다른 두 개의 정산 이벤트가 발생하기 때문에 makePayoutCandidateItem을 두 번 호출하는 것이다.

### MarketPolicy.java
- calculateSalePriceWithoutFee : 판매자에게 실제로 지급되는 금액
- calculatePayoutFee : 판매가 − 지급 금액 = 수수료

---

## 5. PayoutItem 모으기

[0045](https://github.com/jhs512/p-14116-1/commit/0045#diff-c1c0e3c8d0ca04e861ea81e9b0d89fff1aff8558975b8b7bcd062638d9a0fe6c)

### [PayoutDataInit](src_02\05_PayoutItem\PayoutDataInit.java)

#### PayoutCollectPayoutItemsMoreUseCase 설명

**정산 대기 항목(PayoutCandidateItem)을 실제 정산 항목(PayoutItem)으로 전환**하는 유스케이스야.

**핵심 흐름**

```
PayoutCandidateItem (정산 후보) → PayoutItem (실제 정산 항목)
```

결제 후 14일이 지난 항목들만 실제 정산에 포함시키는 로직이야.

---

#### 메서드별 설명

1. **findPayoutReadyCandidateItems (정산 가능한 후보 조회)**

```java
LocalDateTime daysAgo = LocalDateTime
        .now()
        .minusDays(PayoutPolicy.PAYOUT_READY_WAITING_DAYS)  // 14일 전
        .toLocalDate()
        .atStartOfDay();  // 해당 날짜 00:00:00
```

조회 조건:
- `payoutItemIsNull` → 아직 정산 처리 안 된 것
- `paymentDateBefore(daysAgo)` → 결제일이 14일 이상 지난 것
- `limit` 개수만큼만 가져옴

2. **collectPayoutItemsMore (메인 로직)**

```java
payoutReadyCandidateItems.stream()
        .collect(Collectors.groupingBy(PayoutCandidateItem::getPayee))  // ①
        .forEach((payee, candidateItems) -> {                           // ②
            Payout payout = findActiveByPayee(payee).get();             // ③

            candidateItems.forEach(item -> {
                PayoutItem payoutItem = payout.addItem(...);            // ④
                item.setPayoutItem(payoutItem);                         // ⑤
            });
        });
```

| 단계 | 설명 |
|------|------|
| ① | 수령인(payee)별로 그룹핑 |
| ② | 각 수령인별로 처리 |
| ③ | 해당 수령인의 활성 정산(payoutDate가 null인 것) 조회 |
| ④ | 정산에 항목 추가 |
| ⑤ | 후보 항목에 정산 항목 연결 (처리 완료 표시) |

3. **findActiveByPayee**

```java
payoutRepository.findByPayeeAndPayoutDateIsNull(payee);
```

`payoutDateIsNull` → 아직 지급되지 않은 진행 중인 정산을 의미

#### 전체 그림

```
[주문 결제]
    ↓
[PayoutCandidateItem 생성] ← 정산 후보로 대기
    ↓ (14일 경과)
[PayoutItem으로 전환] ← 이 코드가 하는 일
    ↓
[Payout에 포함]
    ↓ (정산일)
[판매자에게 지급]
```

쿠팡이나 네이버 스마트스토어에서 "정산 예정 → 정산 완료"로 넘어가는 과정과 동일한 개념이야.

### [PayoutCollectPayoutItemsMoreUseCase](src_02\05_PayoutItem\PayoutCollectPayoutItemsMoreUseCase.java)
- 제시된 Limit만큼 PayoutCandidateItem을 PayoutItem으로 변환하여 수집

### [PayoutPolicy](src_02\05_PayoutItem\PayoutPolicy.java)
- `PAYOUT_READY_WAITING_DAYS`는 정산 대기 일수를 의미한다.
- [application.yml](src_02\05_PayoutItem\application.yml)에 14일로 설정되어 있다.
- 그렇기에 주문 결제(paymentDate) 후 14일이 지나야 정산 대상(PayoutCandidateItem)이 실제 정산 항목(PayoutItem)으로 전환된다.

### [Util](src_02\05_PayoutItem\Util.java)

이 코드는 **Java 리플렉션을 사용해서 객체의 private 필드 값을 강제로 변경**하는 유틸리티야.

```java
var field = obj.getClass().getDeclaredField(fieldName);
```
→ 객체의 클래스에서 `fieldName`에 해당하는 필드 정보를 가져옴

```java
field.setAccessible(true);
```
→ private 필드여도 접근 가능하게 만듦 (접근 제어 무시)

```java
field.set(obj, value);
```
→ 해당 필드에 새 값을 설정

#### 사용 예시 ([PayoutDataInit](src_02\05_PayoutItem\PayoutDataInit.java)에서)

```java
Util.reflection.setField(
    item,
    "paymentDate",
    LocalDateTime.now().minusDays(PayoutPolicy.PAYOUT_READY_WAITING_DAYS + 1)
);
```

`PayoutCandidateItem`의 `paymentDate`가 private이고 setter가 없어도, 리플렉션으로 강제로 14일 전 날짜로 변경하는 거야.

#### 왜 쓰는가?

**테스트/초기 데이터 세팅 용도**로 주로 사용해:
- 정상적인 방법으로는 수정 불가능한 필드를 변경해야 할 때
- 실제 14일을 기다릴 수 없으니, paymentDate를 과거로 조작해서 정산 테스트

#### 주의점

- **프로덕션 코드에서는 지양**해야 함 (캡슐화 위반)
- 테스트나 DataInit 같은 개발/테스트 환경에서만 사용하는 게 좋음

---

## 6. SpringBatch 의존성 추가, 개발/테스트 환경에서는 h2용 배치 메타데이터 테이블 생성

[0046](https://github.com/jhs512/p-14116-1/commit/0046#diff-dc0d7f925e3e82631ead9a14d8f484303f911f22a957976694e23f09a5351c1e)

### [build.gradle](src_02\06_SpringBatch\build.gradle)
```gradle
  implementation 'org.springframework.boot:spring-boot-starter-batch'
  testImplementation 'org.springframework.batch:spring-batch-test'
```

### [application.yml](src_02\06_SpringBatch\application.yml)

```yaml
spring:
  batch:
    initialize-schema: always
```

### Spring Batch란?

대용량 데이터를 일괄 처리(Batch Processing)하기 위한 Spring 프레임워크

#### API vs Batch
```
실시간 처리 (API)          vs          배치 처리 (Batch)
─────────────────                    ─────────────────
사용자 요청 → 즉시 응답                 정해진 시간에 대량 작업 실행
주문하기, 로그인                        정산, 리포트 생성, 데이터 마이그레이션
```
#### Spring Batch 핵심 구조
```
┌─────────────────────────────────────────────────┐
│                     Job                         │
│  (하나의 배치 작업 단위)                           │
│                                                 │
│   ┌─────────┐   ┌─────────┐   ┌─────────┐       │
│   │  Step1  │ → │  Step2  │ → │  Step3  │       │
│   │ 데이터   │   │ 가공     │   │ 저장    │       │
│   │ 읽기     │   │         │   │         │       │
│   └─────────┘   └─────────┘   └─────────┘       │
└─────────────────────────────────────────────────┘
```

#### 메타데이터 테이블이 필요한 이유
```plain text
Q: 어제 정산 Job이 50만 건 처리 중 서버가 죽었어. 오늘 다시 실행하면?

Spring Batch 없이: 처음부터 다시 100만 건 처리 😱
Spring Batch 사용: 메타데이터 확인 → 50만 건부터 이어서 처리 ✓
```

### [BatchConfig](src_02\06_SpringBatch\BatchConfig.java)

이 코드는 **Spring Batch의 메타데이터 테이블을 초기화**하는 설정 클래스야.

#### 핵심 구성요소

**1. 클래스 레벨 어노테이션**

```java
@Configuration          // Spring 설정 클래스
@EnableBatchProcessing  // Spring Batch 기능 활성화
@EnableJdbcJobRepository // JDBC 기반 JobRepository 사용
```

**2. DataSourceInitializer 빈**

```java
@Bean
@Profile("!prod")  // prod 프로파일이 아닐 때만 실행 (dev, test 등)
public DataSourceInitializer notProdDataSourceInitializer(DataSource dataSource) {
```

- **개발/테스트 환경에서만** H2 스키마를 자동 생성
- 운영 환경에서는 수동으로 스키마를 관리하겠다는 의도

**3. 스키마 초기화 로직**

```java
// ResourceDatabasePopulator = SQL 스크립트 파일을 읽어서 데이터베이스에 실행해주는 Spring 유틸리티 클래스
ResourceDatabasePopulator populator = new ResourceDatabasePopulator();

// Spring Batch가 제공하는 H2용 스키마 SQL 파일
// 실행할 SQL 스크립트 추가
populator.addScript(new ClassPathResource("/org/springframework/batch/core/schema-h2.sql"));

// 이미 테이블이 있어도 에러 무시하고 계속 진행
populator.setContinueOnError(true);

DataSourceInitializer initializer = new DataSourceInitializer();
initializer.setDataSource(dataSource);
initializer.setDatabasePopulator(populator); // 앱 시작 시 실행
return initializer;
```

---

#### Spring Batch 메타데이터 테이블이 뭐야?

Spring Batch는 Job 실행 이력을 추적하기 위해 **6개의 테이블**이 필요해:

| 테이블 | 용도 |
|--------|------|
| `BATCH_JOB_INSTANCE` | Job 인스턴스 정보 |
| `BATCH_JOB_EXECUTION` | Job 실행 이력 |
| `BATCH_JOB_EXECUTION_PARAMS` | 실행 파라미터 |
| `BATCH_STEP_EXECUTION` | Step 실행 이력 |
| `BATCH_STEP_EXECUTION_CONTEXT` | Step 컨텍스트 |
| `BATCH_JOB_EXECUTION_CONTEXT` | Job 컨텍스트 |

---

#### 왜 이렇게 설정하나?

```
개발 환경 (H2 인메모리)
├── 앱 시작할 때마다 DB 초기화됨
├── 매번 스키마 자동 생성 필요
└── @Profile("!prod")로 자동 실행

운영 환경 (MySQL, PostgreSQL 등)
├── 스키마가 이미 존재
├── DBA가 수동으로 관리
└── 자동 생성하면 위험 → 제외
```

#### 참고: DB별 스키마 파일

Spring Batch는 각 DB에 맞는 스키마 파일을 제공해:

```
/org/springframework/batch/core/schema-h2.sql
/org/springframework/batch/core/schema-mysql.sql
/org/springframework/batch/core/schema-postgresql.sql
/org/springframework/batch/core/schema-oracle.sql
```

운영에서 MySQL 쓴다면 `schema-mysql.sql`을 직접 실행하면 돼.

---

## 7. PayoutItem 모으기를 배치 잡으로 수행

[0047](https://github.com/jhs512/p-14116-1/commit/0047)

