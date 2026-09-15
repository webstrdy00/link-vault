import { assertEquals, assertRejects } from "@std/assert";
import { encode as encodeJpeg } from "jpeg-js";
import {
  AssetImageError,
  type AssetImageErrorCode,
  verifyAssetImage,
} from "./asset-image.ts";

const JPEG = encodeJpeg({
  width: 1,
  height: 1,
  data: new Uint8Array([30, 90, 180, 255]),
}, 90).data;
const PNG = fromBase64(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
);
const WEBP = fromBase64(
  "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA",
);

Deno.test("verifyAssetImage fully decodes valid JPEG, PNG, and WebP images", async () => {
  for (
    const [bytes, mimeType] of [
      [JPEG, "image/jpeg"],
      [PNG, "image/png"],
      [WEBP, "image/webp"],
    ] as const
  ) {
    assertEquals(await verifyAssetImage(bytes, mimeType), {
      mime_type: mimeType,
      byte_size: bytes.byteLength,
      width: 1,
      height: 1,
    });
  }
});

Deno.test("verifyAssetImage decodes a valid static extended WebP", async () => {
  const extendedWebp = staticExtendedWebpFixture();

  assertEquals(await verifyAssetImage(extendedWebp, "image/webp"), {
    mime_type: "image/webp",
    byte_size: extendedWebp.byteLength,
    width: 1,
    height: 1,
  });
});

Deno.test("verifyAssetImage rejects truncated compressed pixels with header and trailer intact", async () => {
  const truncatedPayload = new Uint8Array(PNG.byteLength - 1);
  truncatedPayload.set(PNG.subarray(0, 51));
  truncatedPayload.set(PNG.subarray(52), 51);
  writeU32Be(truncatedPayload, 33, 10);

  await assertErrorCode(
    "INVALID_IMAGE",
    () => verifyAssetImage(truncatedPayload, "image/png"),
  );
});

Deno.test("verifyAssetImage rejects a truncated image header", async () => {
  await assertErrorCode(
    "INVALID_IMAGE",
    () => verifyAssetImage(PNG.slice(0, 20), "image/png"),
  );
});

Deno.test("verifyAssetImage rejects MIME content mismatches", async () => {
  await assertErrorCode(
    "ASSET_MIME_MISMATCH",
    () => verifyAssetImage(PNG, "image/jpeg"),
  );
});

Deno.test("verifyAssetImage rejects assets over two million bytes before parsing", async () => {
  await assertErrorCode(
    "ASSET_TOO_LARGE",
    () => verifyAssetImage(new Uint8Array(2_000_001), "image/png"),
  );
});

Deno.test("verifyAssetImage guards dimensions before allocating decoded pixels", async () => {
  const oversizedDimensions = PNG.slice();
  writeU32Be(oversizedDimensions, 16, 6_000);
  writeU32Be(oversizedDimensions, 20, 5_000);

  await assertErrorCode(
    "IMAGE_DIMENSIONS_EXCEEDED",
    () => verifyAssetImage(oversizedDimensions, "image/png"),
  );
});

Deno.test("verifyAssetImage rejects animated WebP containers", async () => {
  await assertErrorCode(
    "INVALID_IMAGE",
    () => verifyAssetImage(animatedWebpFixture(), "image/webp"),
  );
});

Deno.test("verifyAssetImage rejects a late VP8X that hides oversized payload dimensions", async () => {
  const disguisedOversizedWebp = WEBP.slice();
  writeU16Le(disguisedOversizedWebp, 26, 6_000);
  writeU16Le(disguisedOversizedWebp, 28, 5_000);

  const lateVp8x = staticExtendedWebpFixture().subarray(12, 30);
  const malformed = new Uint8Array(
    disguisedOversizedWebp.byteLength + lateVp8x.byteLength,
  );
  malformed.set(disguisedOversizedWebp);
  malformed.set(lateVp8x, disguisedOversizedWebp.byteLength);
  writeU32Le(malformed, 4, malformed.byteLength - 8);

  await assertErrorCode(
    "INVALID_IMAGE",
    () => verifyAssetImage(malformed, "image/webp"),
  );
});

Deno.test("verifyAssetImage rejects duplicate WebP image payloads", async () => {
  const duplicateChunk = WEBP.subarray(12);
  const malformed = new Uint8Array(WEBP.byteLength + duplicateChunk.byteLength);
  malformed.set(WEBP);
  malformed.set(duplicateChunk, WEBP.byteLength);
  writeU32Le(malformed, 4, malformed.byteLength - 8);

  await assertErrorCode(
    "INVALID_IMAGE",
    () => verifyAssetImage(malformed, "image/webp"),
  );
});

async function assertErrorCode(
  expected: AssetImageErrorCode,
  action: () => Promise<unknown>,
): Promise<void> {
  const error = await assertRejects(action, AssetImageError);
  assertEquals((error as AssetImageError).code, expected);
}

function animatedWebpFixture(): Uint8Array {
  const frameImageChunk = WEBP.subarray(12);
  const bytes = new Uint8Array(12 + 18 + 14 + 8 + 16 + frameImageChunk.length);
  writeAscii(bytes, 0, "RIFF");
  writeU32Le(bytes, 4, bytes.byteLength - 8);
  writeAscii(bytes, 8, "WEBP");

  let cursor = 12;
  writeAscii(bytes, cursor, "VP8X");
  writeU32Le(bytes, cursor + 4, 10);
  bytes[cursor + 8] = 0x02;
  cursor += 18;

  writeAscii(bytes, cursor, "ANIM");
  writeU32Le(bytes, cursor + 4, 6);
  cursor += 14;

  writeAscii(bytes, cursor, "ANMF");
  writeU32Le(bytes, cursor + 4, 16 + frameImageChunk.length);
  bytes[cursor + 8 + 12] = 100;
  bytes.set(frameImageChunk, cursor + 8 + 16);
  return bytes;
}

function staticExtendedWebpFixture(): Uint8Array {
  const imageChunk = WEBP.subarray(12);
  const bytes = new Uint8Array(12 + 18 + imageChunk.byteLength);
  writeAscii(bytes, 0, "RIFF");
  writeU32Le(bytes, 4, bytes.byteLength - 8);
  writeAscii(bytes, 8, "WEBP");
  writeAscii(bytes, 12, "VP8X");
  writeU32Le(bytes, 16, 10);
  bytes.set(imageChunk, 30);
  return bytes;
}

function fromBase64(value: string): Uint8Array {
  return Uint8Array.from(atob(value), (character) => character.charCodeAt(0));
}

function writeAscii(bytes: Uint8Array, offset: number, value: string): void {
  for (let index = 0; index < value.length; index++) {
    bytes[offset + index] = value.charCodeAt(index);
  }
}

function writeU32Be(bytes: Uint8Array, offset: number, value: number): void {
  new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).setUint32(
    offset,
    value,
  );
}

function writeU32Le(bytes: Uint8Array, offset: number, value: number): void {
  new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).setUint32(
    offset,
    value,
    true,
  );
}

function writeU16Le(bytes: Uint8Array, offset: number, value: number): void {
  new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).setUint16(
    offset,
    value,
    true,
  );
}
