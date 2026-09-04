# Generates the speech sample the Whisper benchmark measures against.
#
# Windows' built-in speech synthesiser is used on purpose: it needs no network,
# no dataset licence and no model download, and it produces the same audio on
# every machine, so a latency number measured here is comparable with one
# measured there. The file itself is not committed — it is regenerated.
#
# The benchmark wants roughly six seconds, matching the utterance length the
# design's 550 ms recognition budget is written against.
#
#     powershell -ExecutionPolicy Bypass -File make-bench-sample.ps1

param(
    [string]$Out  = "$PSScriptRoot\bench-sample.wav",
    [string]$Text = "In the backend project, fix the failing tests in the authentication module."
)

Add-Type -AssemblyName System.Speech

$synth  = New-Object System.Speech.Synthesis.SpeechSynthesizer
$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(
    16000,
    [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
    [System.Speech.AudioFormat.AudioChannel]::Mono)

$synth.SetOutputToWaveFile($Out, $format)
$synth.Speak($Text)
$synth.Dispose()

$bytes    = (Get-Item $Out).Length
$seconds  = [math]::Round(($bytes - 44) / (16000.0 * 2), 2)
Write-Output "$Out  ($seconds s, 16 kHz mono)"

# No Russian voice ships with Windows by default, so this sample is English.
# Russian decodes to more tokens per second of speech, which means a latency
# figure measured here is a lower bound for the Russian profile, not a
# stand-in for it.
