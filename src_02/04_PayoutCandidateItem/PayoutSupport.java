package com.back.boundedContext.payout.app;

import com.back.boundedContext.payout.domain.PayoutMember;
import com.back.boundedContext.payout.out.PayoutMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PayoutSupport {
    private final PayoutMemberRepository payoutMemberRepository;

    public Optional<PayoutMember> findHolingMember() {
        return payoutMemberRepository.findByUsername("holding");
    }

    public Optional<PayoutMember> findMemberById(int id) {
        return payoutMemberRepository.findById(id);
    }
}