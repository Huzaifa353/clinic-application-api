package com.preclinic.backend.common;

import java.util.List;

/** List envelope used by every paged endpoint; mirrors the frontend's {@code apiResultFormat}. */
public record PageResult<T>(List<T> data, long totalData) {
}
