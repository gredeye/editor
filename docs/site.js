'use strict';
(async () => {
  const repo = 'gredeye/editor';
  const button = document.getElementById('download');
  const status = document.getElementById('release-status');
  try {
    const response = await fetch(`https://api.github.com/repos/${repo}/releases/latest`, {
      headers: { Accept: 'application/vnd.github+json' }, signal: AbortSignal.timeout(10000)
    });
    if (response.status === 404) {
      button.textContent = 'Release not published yet';
      button.setAttribute('aria-disabled', 'true');
      button.removeAttribute('href');
      status.textContent = 'Source is available on GitHub. A signed APK will appear after the first successful release.';
      return;
    }
    if (!response.ok) throw new Error('Release API unavailable');
    const release = await response.json();
    const asset = release.assets.find(a => a.name === 'Vynox.apk');
    if (!asset) throw new Error('Release has no Vynox.apk asset');
    // Keep a stable latest-release URL; never interpolate API data into HTML.
    document.getElementById('version').textContent = `Vynox ${release.tag_name}`;
    document.getElementById('notes').textContent = release.body || 'See GitHub Releases for details.';
    status.textContent = `${release.tag_name} · Android 10+ · ${(asset.size / 1048576).toFixed(1)} MB · ${new Date(release.published_at).toLocaleDateString()}`;
  } catch {
    status.textContent = 'Live release information unavailable. The download link always targets the latest published APK; check GitHub Releases if none is available.';
  }
})();
