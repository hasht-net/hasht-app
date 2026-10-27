
precondition(AttachmentFileURL.parse("https://cdn.example/file?sig=x", secure: true) != nil)
precondition(AttachmentFileURL.parse("http://chat.local/file", secure: false) != nil)
for raw in ["file:///private/file", "https://user:pass@example.com/a", "http://example.com/a", "javascript:alert(1)", "https:///missing-host"] {
    precondition(AttachmentFileURL.parse(raw, secure: true) == nil, raw)
}
precondition(AttachmentFileURL.filename("../../notes.txt") == ".._.._notes.txt")
precondition(AttachmentFileURL.filename("..") == "attachment")
precondition(AttachmentFileURL.filename("a\nb.txt") == "a_b.txt")
print("Attachment URL, HTTPS redirect and filename validation passed")
let generation = AttachmentActionGeneration()
let firstSession = generation.snapshot()
precondition(generation.matches(firstSession))
generation.invalidate()
precondition(!generation.matches(firstSession))
precondition(generation.matches(generation.snapshot()))
print("Same-entry sign-out invalidates attachment presentation")
