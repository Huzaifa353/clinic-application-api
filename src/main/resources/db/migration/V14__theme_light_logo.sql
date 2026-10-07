-- V14: the Theme settings page has three images (logo, light logo, favicon); V2 only had two columns.
alter table clinic_settings
    add column theme_light_logo_file_id bigint references file_asset(id);
