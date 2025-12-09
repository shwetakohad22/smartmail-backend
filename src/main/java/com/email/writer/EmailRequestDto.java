package com.email.writer;

import lombok.Data;

@Data
public class EmailRequestDto {
    // For REPLY: pass the original email text (from the thread)
    // For COMPOSE: pass the user's instruction/prompt
    private String emailContent;

    // "professional" | "casual" | "friendly" (optional)
    private String tone;

    // true => reply mode, false => compose mode
    private Boolean isReply;
}
