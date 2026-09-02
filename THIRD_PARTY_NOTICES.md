---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: '4ad9ed87-ee15-4e65-b2e1-1d4fae2266ec'
  PropagateID: '4ad9ed87-ee15-4e65-b2e1-1d4fae2266ec'
  ReservedCode1: 'a9ad182f-616f-4850-807a-88f602cdb9ba'
  ReservedCode2: 'a9ad182f-616f-4850-807a-88f602cdb9ba'
---

# Third-party notices

## sub2api

- Project: [Wei-Shaw/sub2api](https://github.com/Wei-Shaw/sub2api)
- Evaluation baseline: `b5827cfd54d58c248a9480b800444d0b40f0c6ea`
- License: GNU Lesser General Public License v3.0 (LGPL-3.0)
- License source: [upstream LICENSE at the pinned commit](https://github.com/Wei-Shaw/sub2api/blob/b5827cfd54d58c248a9480b800444d0b40f0c6ea/LICENSE)
- Local license copy: `docs/licenses/sub2api-LGPL-3.0.txt`

Linknux reviewed sub2api's product behavior and independently implemented selected concepts in the existing Java/Vue architecture. No Go runtime or verbatim Go source file is included in this change. Any future source-level adaptation must retain the applicable copyright, LGPL license, and a description of modifications.

## BazaarLink LLMprobe-engine

- Project: [Bazaarlinkorg/LLMprobe-engine](https://github.com/Bazaarlinkorg/LLMprobe-engine)
- License: GNU Affero General Public License v3.0 (AGPL-3.0)
- Local license copy: `model-probe/vendor/llmprobe-engine/LICENSE`
- Usage: LLMprobe-engine runs as an isolated Node.js sidecar process (`model-probe/src/server.js`) on the loopback interface. The Spring Boot backend only talks to the sidecar over HTTP and does not link against AGPL code in the Java process. The vendored engine source remains under the sidecar directory so its obligations stay clearly separated from the main application.
- Distribution note: Any redistribution or service offering that exposes the LLMprobe-engine functionality (including via network) must comply with the AGPL-3.0 terms, including offering the corresponding source of the engine to its users. Review deployment/licensing expectations before enabling the model-probe feature for end users.