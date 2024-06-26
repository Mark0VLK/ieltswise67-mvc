package com.ieltswise.mapper.impl;

import com.ieltswise.controller.request.PaymentCredentialsRequest;
import com.ieltswise.entity.PaymentCredentials;
import com.ieltswise.exception.EmailNotFoundException;
import com.ieltswise.mapper.PaymentCredentialsMapper;
import com.ieltswise.repository.PaymentCredentialsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PaymentCredentialsMapperImpl implements PaymentCredentialsMapper {

    private final PaymentCredentialsRepository paymentCredentialsRepository;

    @Autowired
    public PaymentCredentialsMapperImpl(PaymentCredentialsRepository paymentCredentialsRepository) {
        this.paymentCredentialsRepository = paymentCredentialsRepository;
    }

    @Override
    public PaymentCredentials mapToPaymentCredentials(PaymentCredentialsRequest paymentCredentialsRequest)
            throws EmailNotFoundException {
        if (paymentCredentialsRequest == null) {
            return null;
        } else {
            String email = paymentCredentialsRequest.getTutorEmail();
            PaymentCredentials paymentCredentials = paymentCredentialsRepository.findByTutorEmail(email).orElseThrow(() ->
                    new EmailNotFoundException("Tutor", email));
            paymentCredentials.setClientId(paymentCredentialsRequest.getClientId());
            paymentCredentials.setClientSecret(paymentCredentialsRequest.getClientSecret());
            return paymentCredentials;
        }
    }
}
