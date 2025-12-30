package com.back.boundedContext.cash.app;

import com.back.boundedContext.cash.domain.CashLog;
import com.back.boundedContext.cash.domain.Wallet;
import com.back.global.eventPublisher.EventPublisher;
import com.back.shared.cash.event.CashOrderPaymentFailedEvent;
import com.back.shared.cash.event.CashOrderPaymentSucceededEvent;
import com.back.shared.market.dto.OrderDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CashCompleteOrderPaymentUseCase {
    private final CashSupport cashSupport;
    private final EventPublisher eventPublisher;

    public void completeOrderPayment(OrderDto order, long pgPaymentAmount) {
        // 1. 지갑 조회
        Wallet customerWallet = cashSupport.findWalletByHolderId(order.getCustomerId()).get();
        Wallet holdingWallet = cashSupport.findHoldingWallet().get();

        // 2. PG 결제한 금액이 있으면 충전
        if (pgPaymentAmount > 0) {
            customerWallet.credit(
                    pgPaymentAmount,
                    CashLog.EventType.충전__PG결제_토스페이먼츠,
                    "Order",
                    order.getId()
            );
        }
        // 이 시점에 고객 지갑 잔액 = 기존 잔액 + PG 결제 금액

        // 3. 충전 후 잔액으로 주문 금액 결제 가능한지 확인
        boolean canPay = customerWallet.getBalance() >= order.getSalePrice();

        if (canPay) {
            // 4-1. 고객 지갑에서 주문 금액 차감
            customerWallet.debit(
                    order.getSalePrice(),
                    CashLog.EventType.사용__주문결제,
                    "Order",
                    order.getId()
            );

            // 4-2. 홀딩 지갑에 임시 보관 (나중에 판매자에게 정산)
            holdingWallet.credit(
                    order.getSalePrice(),
                    CashLog.EventType.임시보관__주문결제,
                    "Order",
                    order.getId()
            );

            // 4-3. 성공 이벤트 발행
            eventPublisher.publish(
                    new CashOrderPaymentSucceededEvent(
                            order,
                            pgPaymentAmount
                    )
            );
        } else {
            // 5. 충전했는데도 잔액이 부족한 경우 (동시성 문제 등)
            eventPublisher.publish(
                    new CashOrderPaymentFailedEvent(
                            "400-1",
                            "충전은 완료했지만 %번 주문을 결제완료처리를 하기에는 예치금이 부족합니다.".formatted(order.getId()),
                            order,
                            pgPaymentAmount,
                            pgPaymentAmount - customerWallet.getBalance()
                    )
            );
        }
    }
}
