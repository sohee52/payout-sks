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

## 2. PayoutMemberCreatedEvent 이벤트 수신 후 Payout 생성

[0042](https://github.com/jhs512/p-14116-1/commit/0042)

- PayoutCreatePayoutUseCase 내에서 PayoutMember에 대한 Payout 생성

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

## 4. 주문 품목 불러오기 후 PayoutCandidateItem 생성

[0044](https://github.com/jhs512/p-14116-1/commit/0044)

### PayoutAddPayoutCandidateItemsUseCase.java에서 makePayoutCandidateItems 메서드가 2번 있는 이유
- 주문 상품 1개에서 “수수료 정산”과 “판매 대금 정산”이라는 서로 다른 두 개의 정산 이벤트가 발생하기 때문에 makePayoutCandidateItem을 두 번 호출하는 것이다.

### MarketPolicy.java
- calculateSalePriceWithoutFee : 판매자에게 실제로 지급되는 금액
- calculatePayoutFee : 판매가 − 지급 금액 = 수수료