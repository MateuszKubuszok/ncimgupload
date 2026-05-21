# Nextcloud API Reference

API documentation relevant to ncimgupload, distilled from research and Nextcloud developer docs.

## Authentication

### App Passwords (recommended)

Generate at: Nextcloud web UI > Settings > Security > App passwords

Use as Basic Auth:
```
Authorization: Basic {base64(username:app_password)}
```

App passwords are scoped to the creating user and can be revoked independently.

### Required Headers

All requests should include:
```
OCS-APIRequest: true
```

## WebDAV File API

Base endpoint: `/remote.php/dav/files/{username}/`

### PROPFIND — List Files

```http
PROPFIND /remote.php/dav/files/alice/Photos/ HTTP/1.1
Host: cloud.example.com
Depth: 1
Content-Type: application/xml

<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:"
            xmlns:oc="http://owncloud.org/ns"
            xmlns:nc="http://nextcloud.org/ns">
  <d:prop>
    <d:displayname/>
    <d:getcontentlength/>
    <d:getlastmodified/>
    <d:getetag/>
    <d:resourcetype/>
    <oc:fileid/>
    <oc:checksums/>
  </d:prop>
</d:propfind>
```

**Depth values:**
- `0` — just the target resource
- `1` — target + immediate children
- `infinity` — entire subtree (use with caution on large directories)

**Response**: HTTP 207 Multi-Status with XML body containing `<d:response>` for each file/directory.

**Key properties:**
- `d:getcontentlength` — file size in bytes
- `d:getlastmodified` — RFC 1123 date (e.g., `Thu, 16 May 2024 10:30:00 GMT`)
- `d:getetag` — changes when file content changes
- `d:resourcetype` — contains `<d:collection/>` for directories
- `oc:fileid` — permanent Nextcloud file ID (survives renames)
- `oc:checksums/oc:checksum` — e.g., `SHA256:abcdef...`

### PUT — Upload File

```http
PUT /remote.php/dav/files/alice/Photos/Phone/IMG_001.jpg HTTP/1.1
Host: cloud.example.com
Content-Type: application/octet-stream
X-NC-WebDAV-AutoMkcol: 1
OC-Checksum: SHA256:abcdef1234567890...

[file bytes]
```

**Headers:**
- `X-NC-WebDAV-AutoMkcol: 1` — auto-creates parent directories
- `OC-Checksum: {algo}:{hex}` — server verifies upload integrity
- `X-OC-Mtime: {unix_seconds}` — preserve original modification time

**Response**: 201 Created (new file) or 204 No Content (overwrite)

### HEAD — Check Existence

```http
HEAD /remote.php/dav/files/alice/Photos/Phone/IMG_001.jpg HTTP/1.1
Host: cloud.example.com
```

**Response**: 200 OK if exists, 404 if not.

## Chunked Upload v2

For large files (>100MB). Three-step protocol.

Base endpoint: `/remote.php/dav/uploads/{username}/`

### Step 1: Create Upload Session

```http
MKCOL /remote.php/dav/uploads/alice/upload-abc123/ HTTP/1.1
Host: cloud.example.com
Destination: /remote.php/dav/files/alice/Photos/Phone/video.mp4
```

Use UUID or timestamp for the session ID. Directory expires after 24h of inactivity.

### Step 2: Upload Chunks

```http
PUT /remote.php/dav/uploads/alice/upload-abc123/00001 HTTP/1.1
Host: cloud.example.com
Content-Type: application/octet-stream
Destination: /remote.php/dav/files/alice/Photos/Phone/video.mp4
OC-Total-Length: 5368709120

[chunk bytes, 5MB-5GB each]
```

Chunk names: sequential numbers (00001, 00002, ..., up to 10000).
Last chunk can be smaller than the configured chunk size.

### Step 3: Assemble

```http
MOVE /remote.php/dav/uploads/alice/upload-abc123/.file HTTP/1.1
Host: cloud.example.com
Destination: /remote.php/dav/files/alice/Photos/Phone/video.mp4
OC-Total-Length: 5368709120
OC-Checksum: SHA256:abcdef...
Overwrite: T
```

The `.file` virtual resource represents the assembled result. MOVE triggers server-side assembly and places the file at the Destination.

### Resuming Interrupted Uploads

PROPFIND the upload directory to see which chunks exist:
```http
PROPFIND /remote.php/dav/uploads/alice/upload-abc123/ HTTP/1.1
Depth: 1
```

Only upload missing chunks, then MOVE to assemble.

### Canceling

```http
DELETE /remote.php/dav/uploads/alice/upload-abc123/ HTTP/1.1
```

## Error Codes

| Code | Meaning |
|---|---|
| 201 | Created (successful upload/mkdir) |
| 204 | No Content (successful overwrite) |
| 207 | Multi-Status (PROPFIND response) |
| 401 | Unauthorized (bad credentials) |
| 404 | Not Found (path doesn't exist) |
| 409 | Conflict (parent directory missing, no AutoMkcol) |
| 412 | Precondition Failed (etag mismatch) |
| 423 | Locked (file is being edited) |
| 507 | Insufficient Storage (quota exceeded) |

## References

- [Nextcloud WebDAV Basic API](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/WebDAV/basic.html)
- [Nextcloud Chunked Upload](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/WebDAV/chunking.html)
- [OCS API Overview](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/OCS/ocs-api-overview.html)
