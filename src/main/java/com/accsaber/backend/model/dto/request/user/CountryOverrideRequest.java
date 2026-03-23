package com.accsaber.backend.model.dto.request.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CountryOverrideRequest {

    @NotBlank
    @Pattern(regexp = "^[A-Z]{2}$", message = "Country must be a 2-letter ISO 3166-1 alpha-2 code (e.g. DE, US)")
    private String country;
}
