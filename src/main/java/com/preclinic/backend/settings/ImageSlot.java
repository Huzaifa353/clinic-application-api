package com.preclinic.backend.settings;

import java.util.Set;

import com.preclinic.backend.file.FileService;

/** The image columns of {@code clinic_settings}. Column names are constants, never user input. */
enum ImageSlot {

	PRINT_LOGO("logo_file_id", FileService.IMAGES),
	PRINT_SIGNATURE("signature_file_id", FileService.IMAGES),
	THEME_LOGO("theme_logo_file_id", FileService.IMAGES),
	THEME_LIGHT_LOGO("theme_light_logo_file_id", FileService.IMAGES),
	THEME_FAVICON("theme_favicon_file_id", FileService.IMAGES_AND_ICO);

	final String column;
	final Set<String> allowedTypes;

	ImageSlot(String column, Set<String> allowedTypes) {
		this.column = column;
		this.allowedTypes = allowedTypes;
	}
}
