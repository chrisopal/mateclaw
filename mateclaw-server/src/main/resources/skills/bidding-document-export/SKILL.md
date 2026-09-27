---
name: bidding-document-export
description: Generate a controlled DOCX candidate from fixed bidding manuscript and format references.
version: 1.0.1
allowed-tools:
  - bidding_export_document
---

# Controlled DOCX candidate export

Use only the server-provided manuscript, template, and format requirement references. Call `bidding_export_document` once with those exact references and the assigned mode. Do not provide paths, URLs, image locations, approval actions, or substitute content. Return only the tool's candidate manifest.
