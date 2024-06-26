package com.ieltswise.controller.response;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Event {

    @NotEmpty(message = "should not be empty")
    @NotNull(message = "is required")
    private ZonedDateTime startDate;

    @NotEmpty(message = "should not be empty")
    @NotNull(message = "is required")
    private ZonedDateTime endDate;

    @NotEmpty(message = "should not be empty")
    @NotNull(message = "is required")
    private String status;
}
