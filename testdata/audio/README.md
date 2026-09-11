# Audio corpora

Two corpora, both versioned together with the code: without them recognition
regression goes unchecked, and RISK-8 stays open.

## `golden/` - golden corpus of commands

Short recordings of the owner's commands in WAV format, 16 kHz, mono, with a
transcript in the same-named `.txt`. Used for WER regression when the
recognition model or execution device changes.

**Rule for adding to it:** any recognition miss is first turned into a
recording here, and only then is the cause fixed. That is the only way not to
fix the same thing twice.

## `negative/` - corpus of false triggers

Background the wake word must not fire on: music, speech from speakers, call
recordings, other people's voices saying the wake word. Used for the PRD's
"false triggers per hour" metric and for the speaker-verification threshold.

Recordings of other people's voices are added only with the speaker's consent.

## Limits

Corpora are kept small: short utterances, not hour-long tracks. Long
background recordings for RISK-8 stay local, in `recordings/`, and never
reach the repository.
