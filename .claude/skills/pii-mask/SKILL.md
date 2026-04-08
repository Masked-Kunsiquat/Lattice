---
name: pii-mask
description: Use this skill to ensure all journal text and user input is masked with UUIDs before persistence or processing.
---

# PII Masking Instructions
When this skill is invoked, follow these rules:
1. **Detection:** Identify any names, nicknames, or contact details in the text.
2. **Masking:** Replace identified names with `[PERSON_{UUID}]` format.
3. **Regex Check:** Ensure the logic uses `\b` (word boundaries) to prevent partial-word masking (e.g., match "Jordan", but skip "Jordanian").
4. **Validation:** Verify that the output string contains zero raw names from the `People` database.