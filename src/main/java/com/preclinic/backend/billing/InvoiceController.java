package com.preclinic.backend.billing;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.preclinic.backend.billing.InvoiceDtos.BillingSummary;
import com.preclinic.backend.billing.InvoiceDtos.CreateInvoiceRequest;
import com.preclinic.backend.billing.InvoiceDtos.InvoiceDto;
import com.preclinic.backend.billing.InvoiceDtos.ReceiptDto;
import com.preclinic.backend.billing.InvoiceDtos.ReceivePaymentRequest;
import com.preclinic.backend.billing.InvoiceDtos.VoidRequest;
import com.preclinic.backend.common.Api;
import com.preclinic.backend.common.PageResult;

import jakarta.validation.Valid;

/**
 * Billing. Both roles view invoices, record payments and print receipts; voiding an invoice changes
 * financial history, so only the doctor may do it.
 */
@RestController
@RequestMapping(Api.V1 + "/invoices")
public class InvoiceController {

	private final InvoiceService invoices;

	public InvoiceController(InvoiceService invoices) {
		this.invoices = invoices;
	}

	@GetMapping
	public PageResult<InvoiceDto> list(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String method,
			@RequestParam(required = false) Long doctorId,
			@RequestParam(required = false) Long patientId,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) String dir,
			@RequestParam(defaultValue = "0") int skip,
			@RequestParam(defaultValue = "10") int limit) {
		return invoices.list(new InvoiceService.Query(from, to, status, method, doctorId, patientId, q, sort, dir,
				Math.max(skip, 0), Math.min(Math.max(limit, 1), 200)));
	}

	@GetMapping("/summary")
	public BillingSummary summary(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Long doctorId) {
		return invoices.summary(from, to, doctorId);
	}

	@GetMapping("/{id}")
	public InvoiceDto get(@PathVariable long id) {
		return invoices.get(id);
	}

	/** A charge outside the queue (walk-in billing); optionally takes the first payment at the same time. */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public InvoiceDto create(@Valid @RequestBody CreateInvoiceRequest r) {
		long id = invoices.create(new InvoiceService.NewInvoice(r.patientId(), r.visitId(), r.doctorId(),
				r.serviceId(), r.description(), r.consultationFee(), r.additionalCharges(), r.discount(),
				r.amountPaid(), r.paymentMethod(), r.reference()));
		return invoices.get(id);
	}

	/** Records one payment and returns the receipt data (payment + the updated invoice) for printing. */
	@PostMapping("/{id}/payments")
	@ResponseStatus(HttpStatus.CREATED)
	public ReceiptDto receivePayment(@PathVariable long id, @Valid @RequestBody ReceivePaymentRequest request) {
		return invoices.receivePayment(id, request);
	}

	@PostMapping("/{id}/void")
	@PreAuthorize("hasRole('DOCTOR')")
	public InvoiceDto voidInvoice(@PathVariable long id, @Valid @RequestBody(required = false) VoidRequest request) {
		return invoices.voidInvoice(id, request == null ? null : request.reason());
	}
}
