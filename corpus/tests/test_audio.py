from pathlib import Path

from corpus.audio import ffmpeg_cmd, to_flac_16k_mono


def test_ffmpeg_cmd_is_16k_mono_flac_and_deterministic():
    cmd = ffmpeg_cmd(Path("in.wav"), Path("out.flac"))
    assert cmd == ffmpeg_cmd(Path("in.wav"), Path("out.flac"))
    assert "-ar" in cmd and cmd[cmd.index("-ar") + 1] == "16000"
    assert cmd[cmd.index("-ac") + 1] == "1"
    assert cmd[cmd.index("-c:a") + 1] == "flac"


def test_to_flac_invokes_the_runner_and_makes_the_parent(tmp_path):
    seen = []
    out = tmp_path / "nested" / "out.flac"
    to_flac_16k_mono(tmp_path / "in.wav", out, runner=lambda cmd: seen.append(cmd))
    assert out.parent.is_dir()
    assert seen and seen[0][0] == "ffmpeg"
