package com.email.writer;

import lombok.Data;

@Data
public class EmailRequestDto {
    private String emailContent;

    private String tone;

    private Boolean isReply;
}
