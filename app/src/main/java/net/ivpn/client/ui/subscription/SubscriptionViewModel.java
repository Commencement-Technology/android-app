package net.ivpn.client.ui.subscription;

/*
 IVPN Android app
 https://github.com/ivpn/android-app
 <p>
 Created by Oleksandr Mykhailenko.
 Copyright (c) 2020 Privatus Limited.
 <p>
 This file is part of the IVPN Android app.
 <p>
 The IVPN Android app is free software: you can redistribute it and/or
 modify it under the terms of the GNU General Public License as published by the Free
 Software Foundation, either version 3 of the License, or (at your option) any later version.
 <p>
 The IVPN Android app is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 details.
 <p>
 You should have received a copy of the GNU General Public License
 along with the IVPN Android app. If not, see <https://www.gnu.org/licenses/>.
*/

import android.app.Activity;
import androidx.databinding.ObservableBoolean;
import androidx.databinding.ObservableField;

import com.android.billingclient.api.SkuDetails;

import net.ivpn.client.common.billing.BillingListener;
import net.ivpn.client.common.billing.BillingManagerWrapper;
import net.ivpn.client.common.billing.Sku;
import net.ivpn.client.common.dagger.ActivityScope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import javax.inject.Inject;

@ActivityScope
public class SubscriptionViewModel implements BillingListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubscriptionViewModel.class);

    public final ObservableBoolean dataLoading = new ObservableBoolean();

    public final ObservableField<SkuDetails> year_pro = new ObservableField<>();
    public final ObservableField<SkuDetails> year_standard = new ObservableField<>();
    public final ObservableField<SkuDetails> month_pro = new ObservableField<>();
    public final ObservableField<SkuDetails> month_standard = new ObservableField<>();
    private final ObservableField<String> currentPlan = new ObservableField<>();

    private BillingManagerWrapper billingManagerWrapper;
    private SubscriptionNavigator navigator;
    private Activity activity;

    @Inject
    SubscriptionViewModel(BillingManagerWrapper billingManagerWrapper) {
        this.billingManagerWrapper = billingManagerWrapper;

        if (billingManagerWrapper.getPurchase() != null) {
            currentPlan.set(billingManagerWrapper.getPurchase().getSku());
        }

        dataLoading.set(true);
    }

    public void setActivity(Activity activity) {
        this.activity = activity;
    }

    public void setNavigator(SubscriptionNavigator navigator) {
        this.navigator = navigator;
    }

    public void start() {
        billingManagerWrapper.setBillingListener(this);
    }

    private void processSkuDetails(List<SkuDetails> skuDetailsList) {
        LOGGER.info("processSkuDetails");
        dataLoading.set(false);
        if (skuDetailsList == null) {
            return;
        }

        for (SkuDetails skuDetails: skuDetailsList) {
            if (skuDetails.getSku().equals(Sku.MONTH_STANDARD_SKU)){
                month_standard.set(skuDetails);
            }
            if (skuDetails.getSku().equals(Sku.MONTH_PRO_SKU)){
                month_pro.set(skuDetails);
            }
            if (skuDetails.getSku().equals(Sku.YEAR_PRO_SKU)){
                year_pro.set(skuDetails);
            }
            if (skuDetails.getSku().equals(Sku.YEAR_STANDARD_SKU)){
                year_standard.set(skuDetails);
            }
        }
    }

    public void purchase(SkuDetails skuDetails) {
        LOGGER.info("Pressed " + skuDetails);
        if (skuDetails == null) {
            return;
        }
        billingManagerWrapper.setSkuDetails(skuDetails);
        billingManagerWrapper.startPurchase(activity);
    }

    public String getPrice(SkuDetails skuDetails) {
        if (skuDetails == null) {
            return null;
        }
        String ending = skuDetails.getSku().contains("month") ? "/month" : "/year";
        return skuDetails.getPrice() + ending;
    }

    public boolean isSubscribedFor(SkuDetails skuDetails) {
        if (skuDetails == null) {
            return false;
        }
        return skuDetails.getSku().equals(currentPlan.get());
    }

    @Override
    public void onInitStateChanged(boolean isInit, int errorCode) {
        LOGGER.info("Is billing manager init? - " + isInit + " , errorCode = " + errorCode);
        if (isInit) {
        } else if (errorCode != 0) {
            handleError(errorCode);
        }
    }

    @Override
    public void onPurchaseStateChanged(BillingManagerWrapper.PurchaseState state) {
        //Nothing to do here
    }

    @Override
    public void onCheckingSkuDetailsSuccess(List<SkuDetails> skuDetailsList) {
        LOGGER.info("onCheckingSkuDetailsSuccess");
        processSkuDetails(skuDetailsList);
    }

    @Override
    public void onPurchaseError(int errorStatus, String errorMessage) {
        //ToDo separate listeners
    }

    @Override
    public void onPurchaseAlreadyDone() {
    }

    @Override
    public void onCreateAccountFinish() {

    }

    @Override
    public void onAddFundsFinish() {

    }

    private void handleError(int error) {
        dataLoading.set(false);
        navigator.showBillingManagerError();
    }

    public void release() {
        billingManagerWrapper.removeBillingListener(this);
    }
}