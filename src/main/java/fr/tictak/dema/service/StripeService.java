package fr.tictak.dema.service;

import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.Collections;

@Service
public class StripeService {

    @Value("${stripe.secret.key}")
    private String stripeSecretKey;

    @Value("${stripe.webhook.secret}")
    private String endpointSecret;

    @PostConstruct
    public void init() {
        Stripe.apiKey = stripeSecretKey;
    }

    public String createCheckoutSession(double preCommissionCost, String clientEmail, String moveId) throws StripeException {
        // Convert amount to cents (Stripe expects the amount in the smallest currency unit)
        long amountInCents = (long) (preCommissionCost * 100);

        // Create a single line item for the move service
        SessionCreateParams.LineItem lineItem = SessionCreateParams.LineItem.builder()
                .setPriceData(
                        SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency("eur") // Set currency to euros
                                .setUnitAmount(amountInCents)
                                .setProductData(
                                        SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                                .setName("Move Service")
                                                .build()
                                )
                                .build()
                )
                .setQuantity(1L)
                .build();

        // Build the Checkout Session parameters
        SessionCreateParams params = SessionCreateParams.builder()
                .addAllLineItem(Collections.singletonList(lineItem))
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setCustomerEmail(clientEmail)
                //.setSuccessUrl("http://localhost:4200/accueil?payment=success")
                //.setCancelUrl("http://localhost:4200/accueil?payment=cancel")
                .setSuccessUrl("https://dev.dema.bramasquare.com/accueil?payment=success")
                .setCancelUrl("https://dev.dema.bramasquare.com/accueil?payment=cancel")
                .putMetadata("moveId", moveId)
                .build();

        // Create the Checkout Session
        Session session = Session.create(params);
        return session.getUrl();
    }

    public Event verifyWebhook(String payload, String sigHeader) throws SignatureVerificationException {
        return Webhook.constructEvent(payload, sigHeader, endpointSecret);
    }
}