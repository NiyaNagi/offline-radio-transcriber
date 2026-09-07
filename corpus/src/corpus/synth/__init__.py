"""Synthetic callsign corpus generation (D22, functional-spec §14A.3).

ULS callsigns -> phonetic expansion -> local neural TTS plus spliced real ISOLET/ATC
letter/digit audio -> a channel learned from Paderborn's parallel clean/degraded pairs ->
labelled audio. Output is structurally barred from the eval fold (FR-TST-9).
"""
