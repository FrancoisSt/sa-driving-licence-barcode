<!-- The orchestrator's relay of the implementer's two questions to the analyst, verbatim except for two framing lines the agent harness added (the verbatim record's SHA-256 is in cleanroom/LOG.md). -->

Two clarification questions on the WI spec (revision 2) have come from the implementer. The orchestrator is relaying them verbatim below; please verify each against the instrumented reference, amend spec/wi-codec.md as revision 3, and add a synthetic vector where one is missing.

Implementer's questions, verbatim:
"1. Sections 7 and 12: if the skip count reads six or more 1 bits and the data then runs out before a 0 bit, I report E2 rather than E3, following "checked after the 0 bit". No vector covers this case.
2. Sections 3 and 12: the reference keeps running after end of data and reports failure at the end. All four implementations stop at the first error instead. The invalid synthetic vectors, each with one expected error, agree with that."

What I need back:
- For question 1: what the reference reports when a stream runs out of data in the middle of an over-long skip prefix, together with the exact rule your spec states. Please add one or two synthetic vectors to spec/test-vectors/wi-synthetic.json: an over-long skip prefix cut by the end of data, and, if they behave differently, one where six or more 1 bits are followed by a 0 bit and then more data.
- For question 2: state normatively in the spec whether a decoder may stop at the first error. If the reported error code can depend on stopping early versus running to the end (for example, E2 happens first but E4 or E5 would have been reported later, or the other way round), define which one is canonical, match what the reference's error code would be, check that every expected_error in wi-synthetic.json follows that rule, and add a vector that tells the two behaviours apart if one exists.
- Keep the revision note at the top current. Report the new SHA-256 of spec/wi-codec.md and of wi-synthetic.json, and exactly what changed, section by section.

Answer in words and rules only. Same clean-room and privacy rules as before. Return everything inline in your final message.
