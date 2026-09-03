"""Mirrors android/.../UrlNormalizerTest.kt — same rules, same expectations."""

from __future__ import annotations

from app.url_normalizer import is_short_link, normalize


def test_rewrites_threads_com_to_the_host_ytdlp_matches() -> None:
    assert normalize(
        "https://www.threads.com/@tanisha.xoco/post/DcgXiF7H9WH"
        "?xmt=AQGO1SlEq5mH_5JC5wUVN4hGLYvCU3Y4TxYwdAvZ&slof=1"
    ) == "https://www.threads.net/@tanisha.xoco/post/DcgXiF7H9WH"
    assert normalize("https://threads.com/@u/post/1") == "https://threads.net/@u/post/1"


def test_routes_vimeo_through_the_player_endpoint() -> None:
    """vimeo.com refuses anonymous extraction; player.vimeo.com still answers."""
    assert normalize(
        "https://vimeo.com/1219875917?share=copy&fl=cl&fe=ci"
    ) == "https://player.vimeo.com/video/1219875917"
    assert normalize("https://www.vimeo.com/1219875917") == (
        "https://player.vimeo.com/video/1219875917"
    )
    # Unlisted videos carry a hash, which the player endpoint takes as ?h=
    assert normalize("https://vimeo.com/76979871/8272103a63") == (
        "https://player.vimeo.com/video/76979871?h=8272103a63"
    )


def test_leaves_non_video_vimeo_paths_alone() -> None:
    assert normalize("https://vimeo.com/channels/staffpicks") == (
        "https://vimeo.com/channels/staffpicks"
    )


def test_strips_tracking_parameters() -> None:
    assert normalize("https://www.instagram.com/reel/abc/?igsh=MXY&utm_source=ig") == (
        "https://www.instagram.com/reel/abc/"
    )
    assert normalize(
        "https://vm.tiktok.com/ZM123/?is_from_webapp=1&sender_device=pc"
    ) == "https://vm.tiktok.com/ZM123/"


def test_keeps_load_bearing_parameters() -> None:
    """A deny-list, not an allow-list: `v` and `list` are load-bearing."""
    assert normalize("https://www.youtube.com/watch?v=abc123&si=TRACK") == (
        "https://www.youtube.com/watch?v=abc123"
    )
    assert normalize("https://www.youtube.com/watch?v=abc&list=PL1&index=2") == (
        "https://www.youtube.com/watch?v=abc&list=PL1&index=2"
    )
    assert normalize("https://www.dailymotion.com/video/x8k2p") == (
        "https://www.dailymotion.com/video/x8k2p"
    )


def test_drops_the_fragment_and_trims() -> None:
    assert normalize("  https://example.com/a#anchor  ") == "https://example.com/a"


def test_leaves_anything_it_cannot_parse_alone() -> None:
    assert normalize("not a url") == "not a url"
    assert normalize("") == ""
    assert normalize("file:///etc/passwd") == "file:///etc/passwd"


def test_preserves_port_and_path() -> None:
    assert normalize("https://example.com:8443/deep/path.mp4?utm_medium=x") == (
        "https://example.com:8443/deep/path.mp4"
    )


def test_recognises_links_worth_resolving_first() -> None:
    for url in (
        "https://pin.it/6jSSzAZ95",
        "https://vm.tiktok.com/ZM123/",
        "https://www.threads.com/share/_vqVsdeS1/",
        "https://fb.watch/abc/",
        "https://youtu.be/abc",
        "https://lnkd.in/xyz",
    ):
        assert is_short_link(url), url

    for url in (
        "https://www.youtube.com/watch?v=abc",
        "https://www.dailymotion.com/video/x1",
        "not a url",
    ):
        assert not is_short_link(url), url
