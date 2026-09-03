"""Mirrors the Kotlin ThreadsParserTest and TeraboxParserTest.

Parsing only — no network. The fetching half is a few lines of httpx; the
parsing is where the risk lives, because it reads markup we don't control.
"""

from __future__ import annotations

from app.extractor import build_plans_from_direct
from app.resolvers import terabox, threads


def page(body: str) -> str:
    return (
        "<html><head>\n"
        '<meta property="og:title" content="Tanisha (@tanisha.xoco) on Threads" />\n'
        '<meta property="og:image" content="https://scontent.cdninstagram.com/v/thumb.jpg" />\n'
        f"</head><body><script>{body}</script></body></html>"
    )


# --- Threads -------------------------------------------------------------


def test_threads_pulls_videos_best_first() -> None:
    html = page(
        r"""{"video_versions":[
          {"type":101,"width":720,"height":1280,"url":"https:\/\/scontent.cdninstagram.com\/v\/hi.mp4?oe=1"},
          {"type":102,"width":480,"height":852,"url":"https:\/\/scontent.cdninstagram.com\/v\/lo.mp4?oe=2"}
        ]}"""
    )
    result = threads.parse(html)
    assert result is not None
    assert result.title == "Tanisha"
    assert result.uploader == "tanisha.xoco"
    assert len(result.media) == 2
    assert result.media[0].height == 1280
    assert result.media[0].label == "1280p MP4"
    # Escaped slashes in the embedded JSON must be resolved.
    assert result.media[0].url.startswith("https://scontent.cdninstagram.com/v/hi.mp4")
    assert result.media[1].height == 852
    assert all(m.kind == "progressive" for m in result.media)


def test_threads_falls_back_to_scanning_for_cdn_mp4s() -> None:
    """Meta reshapes these payloads; an unknown shape must still yield media."""
    html = page(
        r"""{"some_new_shape":{"playable":"https:\/\/scontent.cdninstagram.com\/v\/clip.mp4?x=1"}}"""
    )
    result = threads.parse(html)
    assert result is not None
    assert len(result.media) == 1
    assert result.media[0].label == "Original quality (MP4)"


def test_threads_does_not_mistake_an_off_cdn_mp4_for_the_video() -> None:
    result = threads.parse(page('{"tracking":"https://example.com/analytics/beacon.mp4"}'))
    assert result is not None
    assert all(m.kind != "progressive" for m in result.media)
    assert all("example.com" not in m.url for m in result.media)


def test_threads_returns_none_when_nothing_is_on_a_media_cdn() -> None:
    html = (
        "<html><head>"
        '<meta property="og:image" content="https://example.com/logo.png" />'
        '</head><body><script>{"x":"https://example.com/a.mp4"}</script></body></html>'
    )
    assert threads.parse(html) is None


def test_threads_offers_images_when_there_is_no_video() -> None:
    html = page(
        r"""{"image_versions2":{"candidates":[
          {"width":1080,"height":1350,"url":"https:\/\/scontent.cdninstagram.com\/v\/big.jpg"},
          {"width":640,"height":800,"url":"https:\/\/scontent.cdninstagram.com\/v\/mid.jpg"},
          {"width":320,"height":400,"url":"https:\/\/scontent.cdninstagram.com\/v\/small.jpg"},
          {"width":150,"height":190,"url":"https:\/\/scontent.cdninstagram.com\/v\/tiny.jpg"}
        ]}}"""
    )
    result = threads.parse(html)
    assert result is not None
    # Capped at three; the same picture at ten sizes isn't a choice.
    assert len(result.media) == 3
    assert all(m.kind == "image" for m in result.media)
    assert result.media[0].label == "Image 1350px (JPG)"


def test_threads_prefers_video_over_images() -> None:
    html = page(
        r"""{"video_versions":[{"height":720,"url":"https:\/\/scontent.cdninstagram.com\/v\/a.mp4"}],
         "image_versions2":{"candidates":[{"height":720,"url":"https:\/\/scontent.cdninstagram.com\/v\/a.jpg"}]}}"""
    )
    result = threads.parse(html)
    assert result is not None
    assert len(result.media) == 1
    assert result.media[0].kind == "progressive"


def test_threads_deduplicates_the_same_video() -> None:
    html = page(
        r"""{"video_versions":[
          {"height":720,"url":"https:\/\/scontent.cdninstagram.com\/v\/a.mp4?token=1"},
          {"height":720,"url":"https:\/\/scontent.cdninstagram.com\/v\/a.mp4?token=2"}
        ]}"""
    )
    result = threads.parse(html)
    assert result is not None and len(result.media) == 1


def test_threads_carries_required_headers() -> None:
    html = page(
        '{"video_versions":[{"height":720,'
        '"url":"https://scontent.cdninstagram.com/v/a.mp4"}]}'
    )
    result = threads.parse(html)
    assert result is not None
    assert "User-Agent" in result.media[0].headers
    assert "Referer" in result.media[0].headers


def test_threads_handles_only_its_own_hosts() -> None:
    for url in (
        "https://www.threads.net/@u/post/1",
        "https://threads.com/@u/post/1",
        "https://www.threads.com/share/abc/",
    ):
        assert threads.handles(url), url
    for url in ("https://instagram.com/p/1", "https://notthreads.net/x", "not a url"):
        assert not threads.handles(url), url


# --- TeraBox -------------------------------------------------------------


def test_terabox_recognises_the_domains_it_rotates_through() -> None:
    for url in (
        "https://terabox.com/s/1abc",
        "https://www.terabox.com/s/1abc",
        "https://1024terabox.com/s/1abc",
        "https://www.4funbox.com/s/1abc",
        "https://nephobox.com/s/1abc",
        "https://terasharelink.com/s/1abc",
        "https://freeterabox.com/s/1abc",
    ):
        assert terabox.handles(url), url

    for url in (
        "https://youtube.com/watch?v=1",
        "https://notterabox.com/s/1abc",
        "https://terabox.com.evil.net/s/1abc",
        "not a url",
    ):
        assert not terabox.handles(url), url


def test_terabox_extracts_the_share_id() -> None:
    """The /s/ form carries a leading "1" the API doesn't want."""
    assert terabox.extract_surl("https://terabox.com/s/1AbCdEf") == "AbCdEf"
    assert terabox.extract_surl("https://terabox.com/s/1AbCdEf?x=1") == "AbCdEf"
    assert terabox.extract_surl(
        "https://terabox.com/wap/share/filelist?surl=AbCdEf"
    ) == "AbCdEf"
    assert terabox.extract_surl("https://terabox.com/main?category=all") is None


def test_terabox_finds_jstoken_in_each_form() -> None:
    token = "A1B2C3D4E5F6A7B8C9D0E1F2A3B4C5D6E7F8A9B0"
    for html in (
        f'<script>var x = {{"jsToken":"{token}"}};</script>',
        f"<script>fn%28%22{token}%22%29</script>",
        f'<script>fn("{token}")</script>',
        f"window.locals = {{jsToken: '{token}'}}",
    ):
        assert terabox.extract_js_token(html) == token, html
    assert terabox.extract_js_token("<html>no token here</html>") is None


def test_terabox_parses_the_file_list() -> None:
    body = """
    {"errno":0,"shareid":123,"uk":456,"list":[
      {"fs_id":9,"isdir":0,"server_filename":"Holiday clip.mp4","size":"48211234",
       "thumbs":{"url1":"https://thumb/s.jpg","url3":"https://thumb/l.jpg"},
       "dlink":"https://d.terabox.com/file/abc?fid=9"}
    ]}
    """
    result = terabox.parse_share_info(body)
    assert result is not None
    assert result.title == "Holiday clip.mp4"
    # Largest thumbnail wins.
    assert result.thumbnail == "https://thumb/l.jpg"
    assert len(result.media) == 1

    media = result.media[0]
    assert media.url == "https://d.terabox.com/file/abc?fid=9"
    assert media.ext == "mp4"
    # Size arrives as a quoted string, not a number.
    assert media.size_bytes == 48_211_234
    assert media.kind == "progressive"
    assert media.headers["Referer"] == "https://www.terabox.com/"


def test_terabox_skips_folders() -> None:
    body = """
    {"errno":0,"list":[
      {"fs_id":1,"isdir":1,"server_filename":"My folder"},
      {"fs_id":2,"isdir":0,"server_filename":"clip.mp4","size":100,
       "dlink":"https://d.terabox.com/file/x"}
    ]}
    """
    result = terabox.parse_share_info(body)
    assert result is not None
    assert len(result.media) == 1
    assert result.media[0].label == "clip.mp4"


def test_terabox_classifies_images_by_extension() -> None:
    body = (
        '{"list":[{"isdir":0,"server_filename":"photo.JPG","size":900,'
        '"dlink":"https://d.terabox.com/file/p"}]}'
    )
    result = terabox.parse_share_info(body)
    assert result is not None
    assert result.media[0].ext == "jpg"
    assert result.media[0].kind == "image"


def test_terabox_returns_none_when_nothing_is_downloadable() -> None:
    """An expired token or private share returns a body with no dlink."""
    assert terabox.parse_share_info('{"errno":2,"list":[]}') is None
    assert terabox.parse_share_info('{"errno":-9,"show_msg":"expired"}') is None
    assert terabox.parse_share_info(
        '{"list":[{"isdir":0,"server_filename":"x.mp4","size":1}]}'
    ) is None
    assert terabox.parse_share_info("not json at all") is None


def test_terabox_builds_its_two_endpoints() -> None:
    assert "surl=abc" in terabox.share_page_url("abc")
    info = terabox.share_info_url("abc", "TOKEN")
    assert "shorturl=abc" in info
    assert "jsToken=TOKEN" in info
    assert "root=1" in info


# --- plans from direct media --------------------------------------------


def test_direct_media_becomes_streamable_plans() -> None:
    """A direct URL is just a progressive source, so streaming needs no new path."""
    body = """
    {"list":[
      {"isdir":0,"server_filename":"one.mp4","size":10,"dlink":"https://d/1"},
      {"isdir":0,"server_filename":"two.jpg","size":20,"dlink":"https://d/2"}
    ]}
    """
    result = terabox.parse_share_info(body)
    assert result is not None

    plans = build_plans_from_direct(result)
    assert list(plans) == ["d-0", "d-1"]

    first = plans["d-0"]
    assert first.kind == "progressive"
    assert first.needs_ffmpeg is False
    assert first.video is not None
    assert first.video.url == "https://d/1"
    assert first.video.headers["Referer"] == "https://www.terabox.com/"
    assert first.filesize == 10

    assert plans["d-1"].kind == "image"
