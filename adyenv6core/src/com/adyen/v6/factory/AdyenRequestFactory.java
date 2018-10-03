/*
 *                        ######
 *                        ######
 *  ############    ####( ######  #####. ######  ############   ############
 *  #############  #####( ######  #####. ######  #############  #############
 *         ######  #####( ######  #####. ######  #####  ######  #####  ######
 *  ###### ######  #####( ######  #####. ######  #####  #####   #####  ######
 *  ###### ######  #####( ######  #####. ######  #####          #####  ######
 *  #############  #############  #############  #############  #####  ######
 *   ############   ############  #############   ############  #####  ######
 *                                       ######
 *                                #############
 *                                ############
 *
 *  Adyen Hybris Extension
 *
 *  Copyright (c) 2017 Adyen B.V.
 *  This file is open source and available under the MIT license.
 *  See the LICENSE file for more info.
 */
package com.adyen.v6.factory;

import com.adyen.Util.Util;
import com.adyen.enums.VatCategory;
import com.adyen.model.*;
import com.adyen.model.additionalData.InvoiceLine;
import com.adyen.model.checkout.PaymentsDetailsRequest;
import com.adyen.model.checkout.PaymentsRequest;
import com.adyen.model.hpp.DirectoryLookupRequest;
import com.adyen.model.modification.CancelOrRefundRequest;
import com.adyen.model.modification.CaptureRequest;
import com.adyen.model.modification.RefundRequest;
import com.adyen.model.recurring.DisableRequest;
import com.adyen.model.recurring.Recurring;
import com.adyen.model.recurring.RecurringDetailsRequest;
import com.adyen.v6.enums.RecurringContractMode;
import com.adyen.v6.model.RequestInfo;
import de.hybris.platform.commercefacades.order.data.CartData;
import de.hybris.platform.commercefacades.order.data.OrderEntryData;
import de.hybris.platform.commercefacades.user.data.AddressData;
import de.hybris.platform.core.model.user.CustomerModel;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import static com.adyen.constants.ApiConstants.PaymentMethod.STORE_DETAILS;
import static com.adyen.constants.BrandCodes.PAYPAL_ECS;
import static com.adyen.v6.constants.Adyenv6coreConstants.*;

public class AdyenRequestFactory {
    private static final Logger LOG = Logger.getLogger(AdyenRequestFactory.class);

    public PaymentRequest3d create3DAuthorizationRequest(final String merchantAccount, final HttpServletRequest request, final String md, final String paRes) {
        return createBasePaymentRequest(new PaymentRequest3d(), request, merchantAccount).set3DRequestData(md, paRes);
    }

    @Deprecated
    public PaymentRequest createAuthorizationRequest(final String merchantAccount,
                                                     final CartData cartData,
                                                     final HttpServletRequest request,
                                                     final CustomerModel customerModel,
                                                     final RecurringContractMode recurringContractMode) {
        String amount = String.valueOf(cartData.getTotalPrice().getValue());
        String currency = cartData.getTotalPrice().getCurrencyIso();
        String reference = cartData.getCode();
        String selectedReference = cartData.getAdyenSelectedReference();

        PaymentRequest paymentRequest = createBasePaymentRequest(new PaymentRequest(), request, merchantAccount).reference(reference).setAmountData(amount, currency);

        if (! StringUtils.isEmpty(cartData.getAdyenEncryptedCardNumber())) {
            paymentRequest.setEncryptedCardNumber(cartData.getAdyenEncryptedCardNumber());
        }
        if (! StringUtils.isEmpty(cartData.getAdyenCardHolder())) {
            paymentRequest.setCardHolder(cartData.getAdyenCardHolder());
        }
        if (! StringUtils.isEmpty(cartData.getAdyenEncryptedExpiryMonth())) {
            paymentRequest.setEncryptedExpiryMonth(cartData.getAdyenEncryptedExpiryMonth());
        }
        if (! StringUtils.isEmpty(cartData.getAdyenEncryptedExpiryYear())) {
            paymentRequest.setEncryptedExpiryYear(cartData.getAdyenEncryptedExpiryYear());
        }
        if (! StringUtils.isEmpty(cartData.getAdyenEncryptedSecurityCode())) {
            paymentRequest.setEncryptedSecurityCode(cartData.getAdyenEncryptedSecurityCode());
        }

        // set shopper details
        if (customerModel != null) {
            paymentRequest.setShopperReference(customerModel.getCustomerID());
            paymentRequest.setShopperEmail(customerModel.getContactEmail());
        }

        // set recurring contract
        if (customerModel != null && PAYMENT_METHOD_CC.equals(cartData.getAdyenPaymentMethod())) {
            Recurring recurring = getRecurringContractType(recurringContractMode, cartData.getAdyenRememberTheseDetails());
            paymentRequest.setRecurring(recurring);
        }

        // if address details are provided added it into the request
        if (cartData.getDeliveryAddress() != null) {
            Address deliveryAddress = setAddressData(cartData.getDeliveryAddress());
            paymentRequest.setDeliveryAddress(deliveryAddress);
        }

        if (cartData.getPaymentInfo().getBillingAddress() != null) {
            // set PhoneNumber if it is provided
            if (cartData.getPaymentInfo().getBillingAddress().getPhone() != null && ! cartData.getPaymentInfo().getBillingAddress().getPhone().isEmpty()) {
                paymentRequest.setTelephoneNumber(cartData.getPaymentInfo().getBillingAddress().getPhone());
            }

            Address billingAddress = setAddressData(cartData.getPaymentInfo().getBillingAddress());
            paymentRequest.setBillingAddress(billingAddress);
        }

        //OneClick
        if (selectedReference != null && ! selectedReference.isEmpty()) {
            paymentRequest.setSelectedRecurringDetailReference(selectedReference);
            paymentRequest.setShopperInteraction(AbstractPaymentRequest.ShopperInteractionEnum.ECOMMERCE);

            //set oneclick
            Recurring recurring = getRecurringContractType(RecurringContractMode.ONECLICK);
            paymentRequest.setRecurring(recurring);
        }

        // OpenInvoice add required additional data
        if (OPENINVOICE_METHODS_API.contains(cartData.getAdyenPaymentMethod())) {
            paymentRequest.selectedBrand(cartData.getAdyenPaymentMethod());
            setOpenInvoiceData(paymentRequest, cartData, customerModel);

            paymentRequest.setShopperName(getShopperNameFromAddress(cartData.getDeliveryAddress()));
        }

        //Set Boleto parameters
        if (cartData.getAdyenPaymentMethod() != null && cartData.getAdyenPaymentMethod().indexOf(PAYMENT_METHOD_BOLETO) == 0) {
            setBoletoData(paymentRequest, cartData);
        }

        //Set Paypal Express Checkout Shortcut parameters
        if (PAYPAL_ECS.equals(cartData.getAdyenPaymentMethod())) {
            setPaypalEcsData(paymentRequest, cartData);
        }

        return paymentRequest;
    }

    public PaymentsDetailsRequest create3DPaymentsRequest(final String paymentData, final String md, final String paRes) {

        PaymentsDetailsRequest paymentsDetailsRequest = new PaymentsDetailsRequest();
        paymentsDetailsRequest.set3DRequestData(md, paRes, paymentData);
        return paymentsDetailsRequest;
    }

    public PaymentsRequest createPaymentsRequest(final String merchantAccount,
                                                 final CartData cartData,
                                                 final RequestInfo requestInfo,
                                                 final CustomerModel customerModel,
                                                 final RecurringContractMode recurringContractMode) {
        String amount = String.valueOf(cartData.getTotalPrice().getValue());
        String currency = cartData.getTotalPrice().getCurrencyIso();
        String reference = cartData.getCode();
        String selectedReference = cartData.getAdyenSelectedReference();

        PaymentsRequest paymentsRequest = new PaymentsRequest();
        String userAgent = requestInfo.getUserAgent();
        String acceptHeader = requestInfo.getAcceptHeader();
        String shopperIP = requestInfo.getShopperIp();

        paymentsRequest.setAmountData(amount, currency).reference(reference).merchantAccount(merchantAccount).addBrowserInfoData(userAgent, acceptHeader).shopperIP(shopperIP);

        if (! StringUtils.isEmpty(cartData.getAdyenEncryptedCardNumber())
                && ! StringUtils.isEmpty(cartData.getAdyenEncryptedExpiryMonth())
                && ! StringUtils.isEmpty(cartData.getAdyenEncryptedExpiryYear())) {
            paymentsRequest.addEncryptedCardData(cartData.getAdyenEncryptedCardNumber(),
                                                 cartData.getAdyenEncryptedExpiryMonth(),
                                                 cartData.getAdyenEncryptedExpiryYear(),
                                                 cartData.getAdyenEncryptedSecurityCode(),
                                                 cartData.getAdyenCardHolder());
        }

        Recurring recurringContract = getRecurringContractType(recurringContractMode);
        Recurring.ContractEnum contractEnum = null;
        if (recurringContract != null) {
            contractEnum = recurringContract.getContract();
        }

        // set shopper details
        if (customerModel != null) {
            paymentsRequest.setShopperReference(customerModel.getCustomerID());
            paymentsRequest.setShopperEmail(customerModel.getContactEmail());

            if (PAYMENT_METHOD_CC.equals(cartData.getAdyenPaymentMethod())) {
                // Set oneclick and recurring preferences
                paymentsRequest.setEnableRecurring(false);
                paymentsRequest.setEnableOneClick(false);

                if (Recurring.ContractEnum.ONECLICK_RECURRING.equals(contractEnum)) {
                    paymentsRequest.setEnableRecurring(true);
                    paymentsRequest.setEnableOneClick(true);
                } else if (Recurring.ContractEnum.ONECLICK.equals(contractEnum)) {
                    paymentsRequest.setEnableOneClick(true);
                } else if (Recurring.ContractEnum.RECURRING.equals(contractEnum)) {
                    paymentsRequest.setEnableRecurring(true);
                }

                // Set storeDetails parameter when shopper selected to have his card details stored
                if( cartData.getAdyenRememberTheseDetails()) {
                    paymentsRequest.getPaymentMethod().put(STORE_DETAILS, "true");
                }
            }
        }

        // if address details are provided added it into the request
        if (cartData.getDeliveryAddress() != null) {
            Address deliveryAddress = setAddressData(cartData.getDeliveryAddress());
            paymentsRequest.setDeliveryAddress(deliveryAddress);
        }

        if (cartData.getPaymentInfo().getBillingAddress() != null) {
            // set PhoneNumber if it is provided
            if (cartData.getPaymentInfo().getBillingAddress().getPhone() != null && ! cartData.getPaymentInfo().getBillingAddress().getPhone().isEmpty()) {
                paymentsRequest.setTelephoneNumber(cartData.getPaymentInfo().getBillingAddress().getPhone());
            }

            Address billingAddress = setAddressData(cartData.getPaymentInfo().getBillingAddress());
            paymentsRequest.setBillingAddress(billingAddress);
        }

        //OneClick
        if (selectedReference != null && ! selectedReference.isEmpty()) {
            paymentsRequest.addOneClickData(selectedReference, cartData.getAdyenEncryptedSecurityCode());
        }

        return paymentsRequest;
    }


    public CaptureRequest createCaptureRequest(final String merchantAccount, final BigDecimal amount, final Currency currency, final String authReference, final String merchantReference) {
        return new CaptureRequest().fillAmount(String.valueOf(amount), currency.getCurrencyCode()).merchantAccount(merchantAccount).originalReference(authReference).reference(merchantReference);
    }

    public CancelOrRefundRequest createCancelOrRefundRequest(final String merchantAccount, final String authReference, final String merchantReference) {
        return new CancelOrRefundRequest().merchantAccount(merchantAccount).originalReference(authReference).reference(merchantReference);
    }

    public RefundRequest createRefundRequest(final String merchantAccount, final BigDecimal amount, final Currency currency, final String authReference, final String merchantReference) {
        return new RefundRequest().fillAmount(String.valueOf(amount), currency.getCurrencyCode()).merchantAccount(merchantAccount).originalReference(authReference).reference(merchantReference);
    }

    public DirectoryLookupRequest createListPaymentMethodsRequest(final BigDecimal amount, final String currency, final String countryCode, final String shopperLocale) {
        Amount amountData = Util.createAmount(amount, currency);

        DirectoryLookupRequest directoryLookupRequest = new DirectoryLookupRequest().setCountryCode(countryCode)
                                                                                    .setMerchantReference("GetPaymentMethods")
                                                                                    .setPaymentAmount(String.valueOf(amountData.getValue()))
                                                                                    .setCurrencyCode(amountData.getCurrency());

        if (! StringUtils.isEmpty(shopperLocale)) {
            directoryLookupRequest.setShopperLocale(shopperLocale);
        }

        return directoryLookupRequest;
    }

    public RecurringDetailsRequest createListRecurringDetailsRequest(final String merchantAccount, final String customerId) {
        return new RecurringDetailsRequest().merchantAccount(merchantAccount).shopperReference(customerId).selectOneClickContract();
    }

    /**
     * Creates a request to disable a recurring contract
     */
    public DisableRequest createDisableRequest(final String merchantAccount, final String customerId, final String recurringReference) {
        return new DisableRequest().merchantAccount(merchantAccount).shopperReference(customerId).recurringDetailReference(recurringReference);
    }

    private <T extends AbstractPaymentRequest> T createBasePaymentRequest(T abstractPaymentRequest, javax.servlet.http.HttpServletRequest request, final String merchantAccount) {
        String userAgent = request.getHeader("User-Agent");
        String acceptHeader = request.getHeader("Accept");
        String shopperIP = request.getRemoteAddr();
        abstractPaymentRequest.merchantAccount(merchantAccount).setBrowserInfoData(userAgent, acceptHeader).shopperIP(shopperIP);

        return abstractPaymentRequest;
    }

    /**
     * Set Address Data into API
     */
    private Address setAddressData(AddressData addressData) {

        Address address = new Address();

        // set defaults because all fields are required into the API
        address.setCity("NA");
        address.setCountry("NA");
        address.setHouseNumberOrName("NA");
        address.setPostalCode("NA");
        address.setStateOrProvince("NA");
        address.setStreet("NA");

        // set the actual values if they are available
        if (addressData.getTown() != null && ! addressData.getTown().isEmpty()) {
            address.setCity(addressData.getTown());
        }

        if (addressData.getCountry() != null && ! addressData.getCountry().getIsocode().isEmpty()) {
            address.setCountry(addressData.getCountry().getIsocode());
        }

        if (addressData.getLine1() != null && ! addressData.getLine1().isEmpty()) {
            address.setStreet(addressData.getLine1());
        }

        if (addressData.getLine2() != null && ! addressData.getLine2().isEmpty()) {
            address.setHouseNumberOrName(addressData.getLine2());
        }

        if (addressData.getPostalCode() != null && ! address.getPostalCode().isEmpty()) {
            address.setPostalCode(addressData.getPostalCode());
        }

        if (addressData.getRegion() != null && ! addressData.getRegion().getIsocode().isEmpty()) {
            address.setStateOrProvince(addressData.getRegion().getIsocode());
        }

        return address;
    }


    /**
     * Return Recurring object from RecurringContractMode
     */
    private Recurring getRecurringContractType(RecurringContractMode recurringContractMode) {
        Recurring recurringContract = new Recurring();

        //If recurring contract is disabled, return null
        if (recurringContractMode == null || RecurringContractMode.NONE.equals(recurringContractMode)) {
            return null;
        }

        String recurringMode = recurringContractMode.getCode();
        Recurring.ContractEnum contractEnum = Recurring.ContractEnum.valueOf(recurringMode);

        recurringContract.contract(contractEnum);

        return recurringContract;
    }

    /**
     * Return the recurringContract. If the user did not want to save the card don't send it as ONECLICK
     */
    private Recurring getRecurringContractType(RecurringContractMode recurringContractMode, final Boolean enableOneClick) {
        Recurring recurringContract = getRecurringContractType(recurringContractMode);

        //If recurring contract is disabled, return null
        if (recurringContract == null) {
            return null;
        }

        // if user want to save his card use the configured recurring contract type
        if (enableOneClick != null && enableOneClick) {
            return recurringContract;
        }

        Recurring.ContractEnum contractEnum = recurringContract.getContract();
        /**
         * If save card is not checked do the folllowing changes:
         * NONE => NONE
         * ONECLICK => NONE
         * ONECLICK,RECURRING => RECURRING
         * RECURRING => RECURRING
         */
        if (Recurring.ContractEnum.ONECLICK_RECURRING.equals(contractEnum) || Recurring.ContractEnum.RECURRING.equals(contractEnum)) {
            return recurringContract.contract(Recurring.ContractEnum.RECURRING);
        }

        return null;
    }

    /**
     * Get shopper name and gender
     */
    private Name getShopperNameFromAddress(AddressData addressData) {
        Name shopperName = new Name();

        shopperName.setFirstName(addressData.getFirstName());
        shopperName.setLastName(addressData.getLastName());
        shopperName.setGender(Name.GenderEnum.UNKNOWN);

        if (addressData.getTitleCode() != null && ! addressData.getTitleCode().isEmpty()) {
            if (addressData.getTitleCode().equals("mrs") || addressData.getTitleCode().equals("miss") || addressData.getTitleCode().equals("ms")) {
                shopperName.setGender(Name.GenderEnum.FEMALE);
            } else {
                shopperName.setGender(Name.GenderEnum.MALE);
            }
        }

        return shopperName;
    }

    /**
     * Set the required fields for using the OpenInvoice API
     */
    public void setOpenInvoiceData(PaymentRequest paymentRequest, CartData cartData, final CustomerModel customerModel) {
        // set date of birth
        if (cartData.getAdyenDob() != null) {
            paymentRequest.setDateOfBirth(cartData.getAdyenDob());
        }

        if (cartData.getAdyenSocialSecurityNumber() != null && ! cartData.getAdyenSocialSecurityNumber().isEmpty()) {
            paymentRequest.setSocialSecurityNumber(cartData.getAdyenSocialSecurityNumber());
        }

        if (cartData.getAdyenDfValue() != null && ! cartData.getAdyenDfValue().isEmpty()) {
            paymentRequest.setDeviceFingerprint(cartData.getAdyenDfValue());
        }

        // set the invoice lines
        List<InvoiceLine> invoiceLines = new ArrayList();
        String currency = cartData.getTotalPrice().getCurrencyIso();

        for (OrderEntryData entry : cartData.getEntries()) {

            // Use totalPrice because the basePrice does include tax as well if you have configured this to be calculated in the price
            BigDecimal pricePerItem = entry.getTotalPrice().getValue().divide(new BigDecimal(entry.getQuantity()));


            String description = "NA";
            if (entry.getProduct().getName() != null && ! entry.getProduct().getName().equals("")) {
                description = entry.getProduct().getName();
            }

            // Tax of total price (included quantity)
            Double tax = entry.getTaxValues().stream().map(taxValue -> taxValue.getAppliedValue()).reduce(0.0, (x, y) -> x = x + y);


            // Calculate Tax per quantitiy
            if (tax > 0) {
                tax = tax / entry.getQuantity().intValue();
            }

            // Calculate price without tax
            Amount itemAmountWithoutTax = Util.createAmount(pricePerItem.subtract(new BigDecimal(tax)), currency);
            Double percentage = entry.getTaxValues().stream().map(taxValue -> taxValue.getValue()).reduce(0.0, (x, y) -> x = x + y) * 100;

            InvoiceLine invoiceLine = new InvoiceLine();
            invoiceLine.setCurrencyCode(currency);
            invoiceLine.setDescription(description);

            /**
             * The price for one item in the invoice line, represented in minor units.
             * The due amount for the item, VAT excluded.
             */
            invoiceLine.setItemAmount(itemAmountWithoutTax.getValue());

            // The VAT due for one item in the invoice line, represented in minor units.
            invoiceLine.setItemVATAmount(Util.createAmount(BigDecimal.valueOf(tax), currency).getValue());

            // The VAT percentage for one item in the invoice line, represented in minor units.
            invoiceLine.setItemVatPercentage(percentage.longValue());

            // The country-specific VAT category a product falls under.  Allowed values: (High,Low,None)
            invoiceLine.setVatCategory(VatCategory.NONE);

            // An unique id for this item. Required for RatePay if the description of each item is not unique.
            if (! entry.getProduct().getCode().isEmpty()) {
                invoiceLine.setItemId(entry.getProduct().getCode());
            }

            invoiceLine.setNumberOfItems(entry.getQuantity().intValue());

            if (entry.getProduct() != null && ! entry.getProduct().getCode().isEmpty()) {
                invoiceLine.setItemId(entry.getProduct().getCode());
            }

            if (entry.getProduct() != null && ! entry.getProduct().getCode().isEmpty()) {
                invoiceLine.setItemId(entry.getProduct().getCode());
            }

            LOG.debug("InvoiceLine Product:" + invoiceLine.toString());
            invoiceLines.add(invoiceLine);

        }

        // Add delivery costs
        if (cartData.getDeliveryCost() != null) {

            InvoiceLine invoiceLine = new InvoiceLine();
            invoiceLine.setCurrencyCode(currency);
            invoiceLine.setDescription("Delivery Costs");
            Amount deliveryAmount = Util.createAmount(cartData.getDeliveryCost().getValue().toString(), currency);
            invoiceLine.setItemAmount(deliveryAmount.getValue());
            invoiceLine.setItemVATAmount(new Long("0"));
            invoiceLine.setItemVatPercentage(new Long("0"));
            invoiceLine.setVatCategory(VatCategory.NONE);
            invoiceLine.setNumberOfItems(1);
            LOG.debug("InvoiceLine DeliveryCosts:" + invoiceLine.toString());
            invoiceLines.add(invoiceLine);
        }

        paymentRequest.setInvoiceLines(invoiceLines);
    }

    /**
     * Set Boleto payment request data
     */
    private void setBoletoData(PaymentRequest paymentRequest, CartData cartData) {
        paymentRequest.selectedBrand(PAYMENT_METHOD_BOLETO_SANTANDER);
        paymentRequest.setSocialSecurityNumber(cartData.getAdyenSocialSecurityNumber());

        Name shopperName = new Name();
        shopperName.setFirstName(cartData.getAdyenFirstName());
        shopperName.setLastName(cartData.getAdyenLastName());
        paymentRequest.setShopperName(shopperName);
    }

    /**
     * Set Paypal ECS request data
     */
    private void setPaypalEcsData(PaymentRequest paymentRequest, CartData cartData) {
        paymentRequest.selectedBrand(PAYPAL_ECS);
        paymentRequest.setPaymentToken(cartData.getAdyenPaymentToken());
    }
}
