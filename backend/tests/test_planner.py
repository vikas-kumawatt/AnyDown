from __future__ import annotations

from app.errors import ErrorCode, classify_extractor_error
from app.extractor import build_plans, pick_fallback_plan
from app.streaming import build_ffmpeg_args, build_filename, content_disposition


def _without_best(info: dict) -> list:
    """Plans minus the "Best available" alias, which duplicates the top row."""
    return [p for p in build_plans(info).values() if p.kind != "best"]


def test_best_available_is_offered_first(youtube_info: dict) -> None:
    plans = list(build_plans(youtube_info).values())
    assert plans[0].kind == "best"
    assert plans[0].label == "Best available"
    # It aliases the top concrete plan rather than inventing a stream.
    assert plans[0].video == plans[1].video


def test_youtube_plans_dedupe_by_height(youtube_info: dict) -> None:
    plans = _without_best(youtube_info)
    heights = [p.height for p in plans if p.height]
    assert heights == sorted(heights, reverse=True)
    assert len(heights) == len(set(heights)), "one entry per resolution"


def test_no_resolution_cap(youtube_info: dict) -> None:
    """Matches the Android build: every resolution the platform reports."""
    labels = [p.label for p in build_plans(youtube_info).values()]
    assert any(label.startswith("1440p") for label in labels)


def test_unsupported_protocol_is_dropped(youtube_info: dict) -> None:
    plans = build_plans(youtube_info)
    assert not any(
        (p.video and p.video.protocol == "rtmp") for p in plans.values()
    )


def test_1080p_becomes_a_merge_with_mp4_container(youtube_info: dict) -> None:
    plan = next(p for p in build_plans(youtube_info).values() if p.height == 1080)
    assert plan.kind == "merge"
    assert plan.needs_ffmpeg is True
    # avc1 (mp4) must win over vp9 (webm) so the output stays MP4.
    assert plan.video is not None and plan.video.ext == "mp4"
    assert plan.audio is not None and plan.audio.ext == "m4a"
    assert plan.container == "mp4"
    assert plan.ext == "mp4"
    assert plan.filesize == 60_000_000 + 3_400_000


def test_progressive_is_preferred_and_needs_no_ffmpeg(youtube_info: dict) -> None:
    plan = next(p for p in build_plans(youtube_info).values() if p.height == 360)
    assert plan.kind == "progressive"
    assert plan.needs_ffmpeg is False


def test_audio_plan_prefers_m4a(youtube_info: dict) -> None:
    plan = next(p for p in build_plans(youtube_info).values() if p.kind == "audio")
    assert plan.ext == "m4a"
    assert plan.audio is not None and plan.audio.url.endswith("audio-140")


def test_plan_ids_are_deterministic(youtube_info: dict) -> None:
    assert set(build_plans(youtube_info)) == set(build_plans(youtube_info))


def test_missing_codec_fields_treated_as_progressive(tiktok_info: dict) -> None:
    plans = _without_best(tiktok_info)
    assert len(plans) == 1
    assert plans[0].kind == "progressive"
    assert plans[0].needs_ffmpeg is False
    assert plans[0].filesize == 2_500_000


def test_hls_progressive_still_routes_through_ffmpeg(hls_info: dict) -> None:
    plan = _without_best(hls_info)[0]
    assert plan.kind == "progressive"
    assert plan.needs_ffmpeg is True
    assert plan.container == "mp4"


def test_size_estimated_from_bitrate_when_absent(hls_info: dict) -> None:
    plan = _without_best(hls_info)[0]
    assert plan.filesize == int(1800.0 * 1000 / 8 * 60)


def test_fallback_picks_best_progressive_under_limit(youtube_info: dict) -> None:
    plans = build_plans(youtube_info)
    fallback = pick_fallback_plan(plans, 20_000_000)
    assert fallback is not None
    assert fallback.kind == "progressive"
    assert fallback.height == 360


def test_fallback_returns_none_when_nothing_fits(youtube_info: dict) -> None:
    assert pick_fallback_plan(build_plans(youtube_info), 1_000) is None


def test_empty_formats_yield_no_plans() -> None:
    assert build_plans({"title": "x", "formats": []}) == {}


# --- ffmpeg argv ---------------------------------------------------------


def test_merge_argv_maps_both_inputs_and_copies(youtube_info: dict) -> None:
    # The mp4 merge specifically: 1440p is webm and lands in Matroska, which
    # correctly carries no -movflags.
    plan = next(
        p for p in build_plans(youtube_info).values()
        if p.kind == "merge" and p.container == "mp4"
    )
    args = build_ffmpeg_args(plan)

    assert args.count("-i") == 2, "two URL inputs, not two pipes"
    assert args[-1] == "pipe:1"
    assert "-map" in args and "0:v:0" in args and "1:a:0" in args
    assert args[args.index("-c") + 1] == "copy", "no re-encode"
    flags = args[args.index("-movflags") + 1]
    assert "frag_keyframe" in flags and "empty_moov" in flags
    assert "pipe:0" not in args, "stdin is never an input"
    # Platform headers must be forwarded per input.
    assert args.count("-headers") == 2
    assert all("User-Agent: Mozilla/5.0\r\n" in a for a in args if "User-Agent" in a)


def test_single_input_argv_maps_everything(hls_info: dict) -> None:
    plan = next(iter(build_plans(hls_info).values()))
    args = build_ffmpeg_args(plan)
    assert args.count("-i") == 1
    assert args[args.index("-map") + 1] == "0"


def test_matroska_used_for_webm_streams(youtube_info: dict) -> None:
    # Drop the mp4 video and m4a audio so only vp9 + opus remain.
    youtube_info["formats"] = [
        f for f in youtube_info["formats"]
        if f["format_id"] in ("248", "251")
    ]
    plan = next(p for p in build_plans(youtube_info).values() if p.kind == "merge")
    assert plan.container == "matroska"
    assert plan.ext == "mkv"
    args = build_ffmpeg_args(plan)
    assert args[args.index("-f") + 1] == "matroska"
    assert "-movflags" not in args


# --- filenames ----------------------------------------------------------


def test_filename_strips_unsafe_characters() -> None:
    # Slashes/colons/angle brackets collapse to "_", trailing junk is trimmed.
    assert build_filename("Example / Video: <test>", "mp4") == "Example _ Video_ _test.mp4"


def test_filename_falls_back_when_title_missing() -> None:
    assert build_filename(None, "mp4") == "download.mp4"
    assert build_filename("///", "mp4") == "download.mp4"


def test_filename_is_truncated() -> None:
    assert len(build_filename("a" * 500, "mp4")) == 124


def test_content_disposition_has_ascii_and_utf8_forms() -> None:
    header = content_disposition("Ünicode ✓.mp4")
    assert header.startswith("attachment; ")
    assert 'filename="' in header
    assert "filename*=UTF-8''" in header
    assert "✓" not in header.split("filename*=")[0]


# --- error classification ----------------------------------------------


def test_private_content_detected() -> None:
    code, _ = classify_extractor_error(
        "ERROR: [instagram] Requested content is not available, "
        "login required to view this account"
    )
    assert code is ErrorCode.PRIVATE_CONTENT


def test_unsupported_url_detected() -> None:
    code, _ = classify_extractor_error("ERROR: Unsupported URL: https://example.com/x")
    assert code is ErrorCode.UNSUPPORTED_URL


def test_removed_content_is_extraction_failed() -> None:
    code, _ = classify_extractor_error("ERROR: [youtube] abc: Video unavailable")
    assert code is ErrorCode.EXTRACTION_FAILED


def test_unknown_error_does_not_guess() -> None:
    code, _ = classify_extractor_error("ERROR: something entirely new")
    assert code is ErrorCode.EXTRACTION_FAILED


def test_ansi_codes_do_not_break_matching() -> None:
    code, _ = classify_extractor_error("\x1b[0;31mERROR:\x1b[0m This video is private")
    assert code is ErrorCode.PRIVATE_CONTENT
