package com.email.writer;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailGeneratorService {

    private final WebClient webClient;

    @Value("${gemini.api.url}")
    private String geminiApiUrl;

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    public String generateEmail(EmailRequestDto req) {
        final boolean isReply = Boolean.TRUE.equals(req.getIsReply());
        final String tone = (req.getTone() == null || req.getTone().isBlank()) ? "professional" : req.getTone().trim();
        final String raw = req.getEmailContent() == null ? "" : req.getEmailContent();
        final String instruction = isReply ? raw : normalizeInstruction(raw);

        log.info("Email generate request -> isReply: {}, tone: {}, content: {}", isReply, tone, firstN(instruction, 160));

        // 1) First attempt
        Map<String, Object> body = buildRequestBody(tone, instruction, isReply, false);
        String text = extractResponseContent(callGemini(body)).trim();

        if (isReply) {
            return text.isBlank() ? "Sorry, I couldn't generate the email content right now." : text;
        }

        // 2) Compose mode: validate; if reply-ish or not a first-person leave request, regenerate once
        if (!isOutboundLeaveRequest(text)) {
            log.warn("Compose output not acceptable (reply/HR or not a first-person leave request). Regenerating.");
            Map<String, Object> bodyHard = buildRequestBody(tone, instruction, false, true);
            String text2 = extractResponseContent(callGemini(bodyHard)).trim();
            if (isOutboundLeaveRequest(text2)) return text2;

            // 3) Still wrong? Fall back to deterministic template that guarantees the right shape
            log.warn("Second attempt failed validation. Falling back to deterministic template.");
            return fallbackLeaveTemplate(instruction);
        }

        return text;
    }


    private Map<String, Object> buildRequestBody(String tone, String instruction, boolean isReply, boolean hardCompose) {
        String userPrompt = isReply ? buildReplyPrompt(tone, instruction) : buildComposePrompt(tone, instruction, hardCompose);

        Map<String, Object> systemInstruction = isReply ? null : Map.of(
            "role", "system",
            "parts", new Object[] {
                Map.of("text",
                    "You draft NEW outbound emails written by the sender to the recipient. " +
                    "This is never a reply. Never write as HR or the recipient. " +
                    "Always write in first person (I, my) as the sender. " +
                    "Do not ask questions; if any details are missing, use reasonable placeholders.")
            }
        );

        if (systemInstruction != null) {
            return Map.of(
                "system_instruction", systemInstruction,
                "contents", new Object[] {
                    Map.of("role", "user", "parts", new Object[] { Map.of("text", userPrompt) })
                }
            );
        } else {
            return Map.of(
                "contents", new Object[] {
                    Map.of("role", "user", "parts", new Object[] { Map.of("text", userPrompt) })
                }
            );
        }
    }

    private String buildReplyPrompt(String tone, String originalEmail) {
        return """
            You are helping me write an email REPLY.
            Write the reply in a %s tone.

            Requirements:
            - This IS a reply to the message below.
            - Do NOT include a subject line.
            - Write in FIRST PERSON (I, my).
            - Keep it concise, clear, and courteous.
            - Include a short greeting and closing.
            - If dates/actions are implied, restate them clearly.

            Original email:
            ---
            %s
            ---
            """.formatted(tone, originalEmail);
    }

    private String buildComposePrompt(String tone, String instruction, boolean hard) {
        String greetingRule =
            (extractGreetingOverride(instruction) != null)
            ? "Start the email EXACTLY with: \"" + extractGreetingOverride(instruction) + "\""
            : "Start the email with: \"Hello mam,\"";

        String baseConstraints = """
            HARD CONSTRAINTS (must follow):
            - This is NOT a reply. Do NOT include phrases like:
              "Thank you for your email", "Regarding your request", "As per your email",
              "I will get back to you", "We will review", or any HR/recipient-style responses.
            - Do NOT include a subject line.
            - Write in FIRST PERSON (I, my) as the sender.
            - Polite, clear, and to the point (about 50–120 words).
            - Include a simple closing like:
              "Best regards,\\n[Your Name]"
            - Do NOT ask questions or request clarifications; if details are missing, use placeholders.
            - %s

            Date handling:
            - If a single date like 9-11-2025 is given, assume day-month-year (09 November 2025).
            """.formatted(greetingRule);

        String extraHard = hard
            ? "\nAdditional rule: Your previous draft resembled a reply. Rewrite this as a NEW outbound email from the sender to their manager. Do not reference 'your email' or 'your request'.\n"
            : "";

        return """
            Draft a NEW email FROM ME (the employee) TO MY MANAGER in a %s tone.

            %s
            %s

            USER INSTRUCTION (intent):
            ---
            %s
            ---

            Example style (reference only; do NOT copy):
            Hello mam,

            I would like to request leave for two days on [dates/period]. I have organized my tasks and arranged coverage with [colleague's name]. I will remain reachable by email for any urgent queries.

            Best regards,
            [Your Name]
            """.formatted(tone, baseConstraints, extraHard, instruction);
    }

    /* -------------------- CALL + PARSE -------------------- */

    private String callGemini(Map<String, Object> requestBody) {
        try {
            return webClient.post()
                .uri(geminiApiUrl + "?key=" + geminiApiKey)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        } catch (Exception e) {
            log.error("Gemini API error: {}", e.getMessage(), e);
            return "";
        }
    }

    private String extractResponseContent(String response) {
        try {
            if (response == null || response.isBlank()) return "";
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response);

            JsonNode candidates = root.path("candidates");
            if (candidates.isArray() && candidates.size() > 0) {
                JsonNode parts = candidates.get(0).path("content").path("parts");
                if (parts.isArray() && parts.size() > 0) {
                    String text = parts.get(0).path("text").asText(null);
                    if (text != null && !text.isBlank()) return text.trim();
                }
                String alt = candidates.get(0).path("content").path("text").asText(null);
                if (alt != null && !alt.isBlank()) return alt.trim();
            }
            return "";
        } catch (Exception e) {
            log.error("Parsing error: {}", e.getMessage(), e);
            return "";
        }
    }

    /* -------------------- VALIDATION + FALLBACK -------------------- */

    private boolean isOutboundLeaveRequest(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.toLowerCase(Locale.ROOT);

        String[] bad = {
            "thank you for your email", "regarding your request", "as per your email",
            "following up on your email", "we will review", "i will get back to you",
            "we will get back to you", "once we receive", "could you confirm", "please clarify",
            "as discussed in your email", "thanks for reaching out"
        };
        for (String p : bad) if (t.contains(p)) return false;

        boolean firstPerson = t.contains(" i ") || t.startsWith("i ") || t.contains("i'm ") || t.contains("i would like");
        boolean leaveIntent = t.contains("leave") || t.contains("holiday") || t.contains("time off") || t.contains("vacation");
        return firstPerson && leaveIntent;
    }

    private String fallbackLeaveTemplate(String instruction) {
        String greeting = extractGreetingOverride(instruction);
        if (greeting == null) greeting = "Hello mam,";

        String duration = null;
        Matcher dur = Pattern.compile("(?i)\\b(\\d{1,2})\\s*(day|days)\\b").matcher(instruction);
        if (dur.find()) {
            String n = dur.group(1);
            duration = n + " " + (n.equals("1") ? "day" : "days");
        }

        String prettyDate = detectAndPrettyDate(instruction); // may be null

        String line;
        if (duration != null && prettyDate != null) {
            line = "I would like to request " + duration + " leave starting " + prettyDate + ".";
        } else if (duration != null) {
            line = "I would like to request " + duration + " leave.";
        } else if (prettyDate != null) {
            line = "I would like to request leave on " + prettyDate + ".";
        } else {
            line = "I would like to request leave.";
        }

        return String.join(
            "\n\n",
            greeting,
            line + " I have organized my tasks and arranged coverage with [colleague's name]. I will remain reachable by email for any urgent queries.",
            "Best regards,\n[Your Name]"
        );
    }

    private String detectAndPrettyDate(String instruction) {
        if (instruction == null) return null;
        Matcher m = Pattern.compile("\\b(\\d{1,2})[-/\\.](\\d{1,2})[-/\\.](\\d{4})\\b").matcher(instruction);
        if (!m.find()) return null;
        int d = Integer.parseInt(m.group(1));
        int mo = Integer.parseInt(m.group(2));
        String y = m.group(3);
        String iso = String.format("%04d-%02d-%02d", Integer.parseInt(y), mo, d); // assume day-month-year
        try {
            LocalDate dt = LocalDate.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE);
            return dt.format(DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH));
        } catch (DateTimeParseException e) {
            // If parsing fails, just return DD-MM-YYYY
            return String.format("%02d-%02d-%s", d, mo, y);
        }
    }

    /* -------------------- NORMALIZATION + UTILS -------------------- */

    private String normalizeInstruction(String raw) {
        String s = raw.trim();
        s = s.replaceFirst("(?i)^\\s*(please\\s+)?(write|draft|generate|create|compose)\\s+(an?\\s+)?(email|mail|message)\\s*(about|regarding|for)?\\s*", "");
        s = s.replaceFirst("(?i)^\\s*(write|draft|generate|create|compose)\\s*(me|for\\s*me)\\s*(an?\\s+)?(email|mail|message)\\s*(about|regarding|for)?\\s*", "");
        s = s.replaceFirst("(?i)^\\s*(i\\s+want|i\\'d\\s+like)\\s+you\\s+to\\s*(write|draft|generate|create|compose)\\s*(an?\\s+)?(email|mail|message)\\s*(about|regarding|for)?\\s*", "");
        s = s.replaceAll("^([\"'`]+)|([\"'`]+)$", "");
        return s.trim();
    }

    private String extractGreetingOverride(String instruction) {
        if (instruction == null) return null;
        Matcher m = Pattern.compile("^(?i)(hello|hi|dear)\\s+[^,\\n]{0,60},").matcher(instruction.trim());
        if (m.find()) return instruction.trim().substring(m.start(), m.end()).trim();
        return null;
    }

    private String firstN(String s, int n) {
        return (s == null) ? "" : (s.length() <= n ? s : s.substring(0, n) + "...");
    }
}
