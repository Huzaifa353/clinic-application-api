package com.preclinic.backend.file;

import java.util.Optional;

/**
 * Identifies an upload from its first bytes instead of trusting the client-supplied content type
 * (which is trivially spoofed). Only the formats the product accepts are recognised.
 */
public final class FileTypes {

	public static final String PNG = "image/png";
	public static final String JPEG = "image/jpeg";
	public static final String WEBP = "image/webp";
	public static final String PDF = "application/pdf";
	/** Windows icon, accepted for the site favicon. */
	public static final String ICO = "image/x-icon";

	private FileTypes() {
	}

	public static Optional<String> detect(byte[] b) {
		if (b == null || b.length < 4) {
			return Optional.empty();
		}
		if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
			return Optional.of(PNG);
		}
		if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
			return Optional.of(JPEG);
		}
		if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
				&& b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
			return Optional.of(WEBP);
		}
		if (b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') {
			return Optional.of(PDF);
		}
		if (b[0] == 0 && b[1] == 0 && b[2] == 1 && b[3] == 0) {
			return Optional.of(ICO);
		}
		return Optional.empty();
	}
}
