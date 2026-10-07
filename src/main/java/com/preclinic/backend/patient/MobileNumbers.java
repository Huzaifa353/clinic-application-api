package com.preclinic.backend.patient;

/**
 * Pakistani mobile-number handling, ported from the frontend: every common way of writing a number
 * (03001234567, +923001234567, 923001234567, 3001234567, "0300-123 4567") normalises to one
 * comparable form, 03XXXXXXXXX.
 */
public final class MobileNumbers {

	private static final int MIN_DIGITS = 7;
	private static final int MAX_DIGITS = 15;

	private MobileNumbers() {
	}

	/** Keeps digits and a leading '+', then maps the country-code forms to the national 0-prefixed form. */
	public static String normalize(String value) {
		if (value == null) {
			return "";
		}
		String v = value.replaceAll("[^0-9+]", "");
		if (v.startsWith("+92")) {
			return "0" + v.substring(3);
		}
		if (v.startsWith("92") && v.length() > 10) {
			return "0" + v.substring(2);
		}
		if (!v.startsWith("0") && !v.startsWith("+") && v.length() == 10) {
			return "0" + v;
		}
		return v;
	}

	/** A usable number has 7-15 digits once normalised (landlines are allowed, not just 03XX mobiles). */
	public static boolean isValid(String normalized) {
		long digits = normalized.chars().filter(Character::isDigit).count();
		return digits >= MIN_DIGITS && digits <= MAX_DIGITS;
	}

	/** The last ten digits, used to match the same number written differently when checking duplicates. */
	public static String lastTen(String value) {
		String digits = value == null ? "" : value.replaceAll("\\D", "");
		return digits.length() <= 10 ? digits : digits.substring(digits.length() - 10);
	}
}
