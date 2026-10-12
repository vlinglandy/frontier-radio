#!/usr/bin/env python3
"""Download public source samples for the optional JVM adapter contract test."""
import argparse
import datetime
import pathlib
import urllib.parse
import urllib.request
import sys

parser = argparse.ArgumentParser()
parser.add_argument("--output", default="build/source-fixtures")
args = parser.parse_args()
output = pathlib.Path(args.output)
output.mkdir(parents=True, exist_ok=True)
today = datetime.datetime.now(datetime.timezone.utc)
cutoff = today - datetime.timedelta(days=7)
query = urllib.parse.urlencode({"q": f"topic:artificial-intelligence pushed:>={cutoff.date()} archived:false", "sort": "stars", "per_page": 30})
sources = {
    "qbitai.xml": "https://www.qbitai.com/feed",
    "infoq.html": "https://www.infoq.cn/",
    "zhidx.html": "https://www.zhidx.com/p/category/" + urllib.parse.quote("人工智能"),
    "qwen.json": "https://api.github.com/orgs/QwenLM/repos?sort=pushed&per_page=30",
    "deepseek.json": "https://api.github.com/orgs/deepseek-ai/repos?sort=pushed&per_page=30",
    "openai.xml": "https://openai.com/news/rss.xml",
    "google.xml": "https://blog.google/technology/ai/rss/",
    "huggingface.xml": "https://huggingface.co/blog/feed.xml",
    "anthropic.html": "https://www.anthropic.com/news",
    "github.json": f"https://api.github.com/search/repositories?{query}",
    "hackernews.json": "https://hn.algolia.com/api/v1/search_by_date?" + urllib.parse.urlencode({"query": "AI", "tags": "story", "numericFilters": f"created_at_i>{int(cutoff.timestamp())}", "hitsPerPage": 50}),
}
failed = False
for name, url in sources.items():
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 AIFrontierRadio/1.1 (personal RSS reader)", "Accept": "*/*"})
        with urllib.request.urlopen(request, timeout=35) as response:
            data = response.read(4 * 1024 * 1024 + 1)
            if len(data) > 4 * 1024 * 1024:
                raise ValueError("payload exceeds 4 MB")
        (output / name).write_bytes(data)
        print(f"OK {name}: {len(data)} bytes")
    except Exception as error:
        failed = True
        print(f"FAIL {name}: {error}", file=sys.stderr)
sys.exit(1 if failed else 0)
