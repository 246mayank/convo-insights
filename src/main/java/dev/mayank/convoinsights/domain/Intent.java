package dev.mayank.convoinsights.domain;

/**
 * Intent taxonomy. The first eleven values are the Bitext top-level categories, which lets the
 * golden set be scored without hand labelling. TECHNICAL_SUPPORT covers the outage / device
 * traffic that dominates the Twitter corpus; OTHER is the explicit "none of the above".
 */
public enum Intent {
    ACCOUNT,
    CANCEL,
    CONTACT,
    DELIVERY,
    FEEDBACK,
    INVOICE,
    ORDER,
    PAYMENT,
    REFUND,
    SHIPPING,
    SUBSCRIPTION,
    TECHNICAL_SUPPORT,
    OTHER
}
