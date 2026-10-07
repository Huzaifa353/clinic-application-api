package com.preclinic.backend.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class InvoiceDtos {

	private InvoiceDtos() {
	}

	/** One payment (money received) against an invoice. Payments are immutable. */
	public record PaymentDto(long id, long receiptNo, String receiptDisplayId, BigDecimal amount, String method,
			String reference, Instant paidAt, String receivedBy) {
	}

	/**
	 * An invoice with everything the Billing screen and the printed invoice/receipt need. {@code paid},
	 * {@code balance} and {@code paymentStatus} (Paid / Partial / Unpaid / Void) are always derived from
	 * the payments, never stored or typed in.
	 */
	public record InvoiceDto(long id, long invoiceNo, String displayId, long patientId, String patientName,
			String patientDisplayId, String mobile, Long visitId, String tokenNo, Long doctorId, String doctorName,
			Long serviceId, String description, Instant issuedAt, BigDecimal consultationFee,
			BigDecimal additionalCharges, BigDecimal discount, BigDecimal total, BigDecimal paid, BigDecimal balance,
			String paymentStatus, boolean voided, String voidReason, Instant voidedAt, List<PaymentDto> payments) {
	}

	public record MethodAmount(String method, BigDecimal amount) {
	}

	/** Cards above the Billing table. Outstanding is a current figure; the others are for the chosen period. */
	public record BillingSummary(BigDecimal collection, BigDecimal billed, BigDecimal outstanding, long invoiceCount,
			long pendingCount, List<MethodAmount> byMethod) {
	}

	public record ReceiptDto(PaymentDto payment, InvoiceDto invoice) {
	}

	public record ReceivePaymentRequest(
			@NotNull @DecimalMin(value = "0.01", message = "must be greater than zero") @DecimalMax("100000000") BigDecimal amount,
			@NotNull @Pattern(regexp = "Cash|Card|Bank Transfer|EasyPaisa|JazzCash") String method,
			@Size(max = 200) String reference) {
	}

	public record VoidRequest(@Size(max = 500) String reason) {
	}

	/** A charge not tied to the queue (walk-in billing); the intake flow creates its own through the service. */
	public record CreateInvoiceRequest(
			@NotNull Long patientId,
			Long visitId,
			Long doctorId,
			Long serviceId,
			@Size(max = 300) String description,
			@DecimalMin("0") @DecimalMax("100000000") BigDecimal consultationFee,
			@DecimalMin("0") @DecimalMax("100000000") BigDecimal additionalCharges,
			@DecimalMin("0") @DecimalMax("100000000") BigDecimal discount,
			@DecimalMin("0") @DecimalMax("100000000") BigDecimal amountPaid,
			@Pattern(regexp = "Cash|Card|Bank Transfer|EasyPaisa|JazzCash") String paymentMethod,
			@Size(max = 200) String reference) {
	}
}
