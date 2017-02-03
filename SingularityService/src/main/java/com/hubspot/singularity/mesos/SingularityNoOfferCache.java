package com.hubspot.singularity.mesos;

import java.util.Collections;
import java.util.List;

import org.apache.mesos.Protos.Offer;
import org.apache.mesos.Protos.OfferID;
import org.apache.mesos.SchedulerDriver;

import com.google.inject.Inject;
import com.google.inject.Singleton;

@Singleton
public class SingularityNoOfferCache implements OfferCache {

  @Inject
  public SingularityNoOfferCache() {
  }

  @Override
  public void cacheOffer(SchedulerDriver driver, long timestamp, Offer offer) {
    driver.declineOffer(offer.getId());
  }

  @Override
  public void rescindOffer(SchedulerDriver driver, OfferID offerId) {
    // no-op
  }

  @Override
  public void useOffer(OfferID offerId) {
    // no-op
  }

  @Override
  public void returnOffer(OfferID offerId) {
    // no-op
  }

  @Override
  public List<Offer> checkoutOffers() {
    return Collections.emptyList();
  }

  @Override
  public List<Offer> peakOffers() {
    return Collections.emptyList();
  }


}
