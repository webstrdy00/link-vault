import { Unzlib } from "fflate";
import { decode as decodePng } from "fast-png";
import { decode as decodeJpeg } from "jpeg-js";
import decodeWebp, { init as initWebp } from "@jsquash/webp/decode.js";

const MAX_ASSET_BYTES = 2_000_000;
const MAX_IMAGE_PIXELS = 24_000_000;
const MAX_PNG_DECODED_BYTES = 96_000_000;
const MAX_PNG_INFLATED_BYTES = 100_000_000;
const MAX_PNG_PROFILE_BYTES = 1_000_000;
const PNG_SIGNATURE = [137, 80, 78, 71, 13, 10, 26, 10] as const;
const EMPTY_BYTES = new Uint8Array(0);

export type AssetImageErrorCode =
  | "INVALID_IMAGE"
  | "ASSET_TOO_LARGE"
  | "IMAGE_DIMENSIONS_EXCEEDED"
  | "ASSET_MIME_MISMATCH";

export class AssetImageError extends Error {
  constructor(readonly code: AssetImageErrorCode) {
    super(code);
    this.name = "AssetImageError";
  }
}

export interface VerifiedAssetImage {
  mime_type: string;
  byte_size: number;
  width: number;
  height: number;
}

type ImageFormat = "jpeg" | "png" | "webp";

interface InspectedImage {
  format: ImageFormat;
  mimeType: string;
  width: number;
  height: number;
  animated: boolean;
  png?: {
    bitDepth: number;
    channels: number;
    interlaced: boolean;
  };
}

// Copy @jsquash/webp@1.5.0/codec/dec/webp_dec.wasm beside this module and
// include it in the Edge function's static_files. This keeps executable code
// pinned and prevents request-time network fetching.
const webpWasmUrl = new URL("./webp_dec.wasm", import.meta.url);
let webpInitialization: Promise<void> | undefined;

export async function verifyAssetImage(
  bytes: Uint8Array,
  expectedMime: string,
): Promise<VerifiedAssetImage> {
  if (bytes.byteLength > MAX_ASSET_BYTES) {
    throw new AssetImageError("ASSET_TOO_LARGE");
  }

  const inspected = inspectImage(bytes);
  if (expectedMime.trim().toLowerCase() !== inspected.mimeType) {
    throw new AssetImageError("ASSET_MIME_MISMATCH");
  }
  if (inspected.animated) {
    throw new AssetImageError("INVALID_IMAGE");
  }
  if (exceedsPixelLimit(inspected.width, inspected.height)) {
    throw new AssetImageError("IMAGE_DIMENSIONS_EXCEEDED");
  }

  switch (inspected.format) {
    case "jpeg":
      fullyDecodeJpeg(bytes, inspected);
      break;
    case "png":
      fullyDecodePng(bytes, inspected);
      break;
    case "webp":
      await fullyDecodeWebp(bytes, inspected);
      break;
  }

  return {
    mime_type: inspected.mimeType,
    byte_size: bytes.byteLength,
    width: inspected.width,
    height: inspected.height,
  };
}

function inspectImage(bytes: Uint8Array): InspectedImage {
  if (bytes[0] === 0xff && bytes[1] === 0xd8) {
    return inspectJpeg(bytes);
  }
  if (hasPrefix(bytes, PNG_SIGNATURE)) {
    return inspectPng(bytes);
  }
  if (
    bytes.byteLength >= 12 &&
    fourCc(bytes, 0) === "RIFF" &&
    fourCc(bytes, 8) === "WEBP"
  ) {
    return inspectWebp(bytes);
  }
  throw new AssetImageError("INVALID_IMAGE");
}

function inspectJpeg(bytes: Uint8Array): InspectedImage {
  let cursor = 2;
  let width = 0;
  let height = 0;
  let sawStartOfFrame = false;
  let sawScan = false;
  let inEntropyData = false;
  let pendingMarker: number | undefined;

  while (cursor < bytes.byteLength || pendingMarker !== undefined) {
    if (pendingMarker === undefined) {
      if (inEntropyData) {
        while (cursor < bytes.byteLength) {
          if (bytes[cursor++] !== 0xff) {
            continue;
          }
          while (cursor < bytes.byteLength && bytes[cursor] === 0xff) {
            cursor++;
          }
          if (cursor >= bytes.byteLength) {
            throw new AssetImageError("INVALID_IMAGE");
          }
          const marker = bytes[cursor++];
          if (
            marker === 0x00 ||
            marker === 0x01 ||
            (marker >= 0xd0 && marker <= 0xd7)
          ) {
            continue;
          }
          pendingMarker = marker;
          inEntropyData = false;
          break;
        }
        if (pendingMarker === undefined) {
          throw new AssetImageError("INVALID_IMAGE");
        }
      } else {
        if (bytes[cursor++] !== 0xff) {
          throw new AssetImageError("INVALID_IMAGE");
        }
        while (cursor < bytes.byteLength && bytes[cursor] === 0xff) {
          cursor++;
        }
        if (cursor >= bytes.byteLength || bytes[cursor] === 0x00) {
          throw new AssetImageError("INVALID_IMAGE");
        }
        pendingMarker = bytes[cursor++];
      }
    }

    const marker = pendingMarker;
    pendingMarker = undefined;

    if (marker === 0xd9) {
      if (
        !sawStartOfFrame ||
        !sawScan ||
        cursor !== bytes.byteLength
      ) {
        throw new AssetImageError("INVALID_IMAGE");
      }
      return {
        format: "jpeg",
        mimeType: "image/jpeg",
        width,
        height,
        animated: false,
      };
    }
    if (
      marker === 0xd8 ||
      marker === 0x00 ||
      (marker >= 0xd0 && marker <= 0xd7)
    ) {
      throw new AssetImageError("INVALID_IMAGE");
    }
    if (marker === 0x01) {
      continue;
    }
    if (cursor + 2 > bytes.byteLength) {
      throw new AssetImageError("INVALID_IMAGE");
    }

    const segmentLength = readU16Be(bytes, cursor);
    if (segmentLength < 2 || cursor + segmentLength > bytes.byteLength) {
      throw new AssetImageError("INVALID_IMAGE");
    }
    const dataStart = cursor + 2;
    const segmentEnd = cursor + segmentLength;

    if (isStartOfFrame(marker)) {
      if (sawStartOfFrame || segmentLength < 8) {
        throw new AssetImageError("INVALID_IMAGE");
      }
      const componentCount = bytes[dataStart + 5];
      if (
        componentCount === 0 ||
        segmentLength !== 8 + 3 * componentCount
      ) {
        throw new AssetImageError("INVALID_IMAGE");
      }
      height = readU16Be(bytes, dataStart + 1);
      width = readU16Be(bytes, dataStart + 3);
      if (width === 0 || height === 0) {
        throw new AssetImageError("INVALID_IMAGE");
      }
      sawStartOfFrame = true;
    } else if (marker === 0xda) {
      if (!sawStartOfFrame || segmentLength < 8) {
        throw new AssetImageError("INVALID_IMAGE");
      }
      const componentCount = bytes[dataStart];
      if (
        componentCount === 0 ||
        segmentLength !== 6 + 2 * componentCount
      ) {
        throw new AssetImageError("INVALID_IMAGE");
      }
      sawScan = true;
      inEntropyData = true;
    }

    cursor = segmentEnd;
  }

  throw new AssetImageError("INVALID_IMAGE");
}

function inspectPng(bytes: Uint8Array): InspectedImage {
  let cursor: number = PNG_SIGNATURE.length;
  let width = 0;
  let height = 0;
  let bitDepth = 0;
  let channels = 0;
  let interlaced = false;
  let animated = false;
  let sawHeader = false;
  let sawImageData = false;
  let imageDataEnded = false;

  while (cursor < bytes.byteLength) {
    if (cursor + 12 > bytes.byteLength) {
      throw new AssetImageError("INVALID_IMAGE");
    }
    const length = readU32Be(bytes, cursor);
    const type = fourCc(bytes, cursor + 4);
    const dataStart = cursor + 8;
    const dataEnd = dataStart + length;
    const chunkEnd = dataEnd + 4;
    if (chunkEnd > bytes.byteLength) {
      throw new AssetImageError("INVALID_IMAGE");
    }

    if (!sawHeader) {
      if (type !== "IHDR" || length !== 13) {
        throw new AssetImageError("INVALID_IMAGE");
      }
      width = readU32Be(bytes, dataStart);
      height = readU32Be(bytes, dataStart + 4);
      bitDepth = bytes[dataStart + 8];
      channels = pngChannelCount(bytes[dataStart + 9], bitDepth);
      if (
        width === 0 ||
        height === 0 ||
        bytes[dataStart + 10] !== 0 ||
        bytes[dataStart + 11] !== 0 ||
        (bytes[dataStart + 12] !== 0 && bytes[dataStart + 12] !== 1)
      ) {
        throw new AssetImageError("INVALID_IMAGE");
      }
      interlaced = bytes[dataStart + 12] === 1;
      sawHeader = true;
    } else if (type === "IHDR") {
      throw new AssetImageError("INVALID_IMAGE");
    }

    if (type === "IDAT") {
      if (imageDataEnded) {
        throw new AssetImageError("INVALID_IMAGE");
      }
      sawImageData = true;
    } else if (sawImageData && type !== "IEND") {
      imageDataEnded = true;
    }

    if (type === "acTL") {
      animated = true;
    }
    if (type === "IEND") {
      if (
        length !== 0 ||
        !sawImageData ||
        chunkEnd !== bytes.byteLength
      ) {
        throw new AssetImageError("INVALID_IMAGE");
      }
      return {
        format: "png",
        mimeType: "image/png",
        width,
        height,
        animated,
        png: { bitDepth, channels, interlaced },
      };
    }

    cursor = chunkEnd;
  }

  throw new AssetImageError("INVALID_IMAGE");
}

function inspectWebp(bytes: Uint8Array): InspectedImage {
  if (readU32Le(bytes, 4) !== bytes.byteLength - 8) {
    throw new AssetImageError("INVALID_IMAGE");
  }

  let cursor = 12;
  let firstChunk = true;
  let width = 0;
  let height = 0;
  let animated = false;
  let extended = false;
  let sawImagePayload = false;

  while (cursor < bytes.byteLength) {
    if (cursor + 8 > bytes.byteLength) {
      throw new AssetImageError("INVALID_IMAGE");
    }
    const type = fourCc(bytes, cursor);
    const length = readU32Le(bytes, cursor + 4);
    const dataStart = cursor + 8;
    const dataEnd = dataStart + length;
    const chunkEnd = dataEnd + (length & 1);
    if (chunkEnd > bytes.byteLength) {
      throw new AssetImageError("INVALID_IMAGE");
    }

    if (firstChunk) {
      if (type !== "VP8 " && type !== "VP8L" && type !== "VP8X") {
        throw new AssetImageError("INVALID_IMAGE");
      }
      firstChunk = false;
    }

    if (type === "VP8X") {
      if (
        cursor !== 12 ||
        extended ||
        length !== 10 ||
        (bytes[dataStart] & 0xc1) !== 0 ||
        bytes[dataStart + 1] !== 0 ||
        bytes[dataStart + 2] !== 0 ||
        bytes[dataStart + 3] !== 0
      ) {
        throw new AssetImageError("INVALID_IMAGE");
      }
      extended = true;
      animated ||= (bytes[dataStart] & 0x02) !== 0;
      width = readU24Le(bytes, dataStart + 4) + 1;
      height = readU24Le(bytes, dataStart + 7) + 1;
    } else if (type === "VP8 ") {
      if (sawImagePayload) {
        throw new AssetImageError("INVALID_IMAGE");
      }
      const dimensions = inspectVp8Dimensions(bytes, dataStart, length);
      if (extended) {
        requireMatchingDimensions(width, height, dimensions);
      } else {
        ({ width, height } = dimensions);
      }
      sawImagePayload = true;
    } else if (type === "VP8L") {
      if (sawImagePayload) {
        throw new AssetImageError("INVALID_IMAGE");
      }
      const dimensions = inspectVp8lDimensions(bytes, dataStart, length);
      if (extended) {
        requireMatchingDimensions(width, height, dimensions);
      } else {
        ({ width, height } = dimensions);
      }
      sawImagePayload = true;
    } else if (type === "ANIM" || type === "ANMF") {
      if (!extended) {
        throw new AssetImageError("INVALID_IMAGE");
      }
      animated = true;
    } else if (!extended) {
      throw new AssetImageError("INVALID_IMAGE");
    }

    cursor = chunkEnd;
  }

  if (
    firstChunk ||
    width === 0 ||
    height === 0 ||
    (!animated && !sawImagePayload) ||
    (animated && sawImagePayload)
  ) {
    throw new AssetImageError("INVALID_IMAGE");
  }

  return {
    format: "webp",
    mimeType: "image/webp",
    width,
    height,
    animated,
  };
}

function requireMatchingDimensions(
  canvasWidth: number,
  canvasHeight: number,
  payload: { width: number; height: number },
): void {
  if (
    payload.width !== canvasWidth ||
    payload.height !== canvasHeight
  ) {
    throw new AssetImageError("INVALID_IMAGE");
  }
}

function fullyDecodeJpeg(bytes: Uint8Array, inspected: InspectedImage): void {
  try {
    const decoded = decodeJpeg(bytes, {
      useTArray: true,
      formatAsRGBA: false,
      tolerantDecoding: false,
      maxResolutionInMP: MAX_IMAGE_PIXELS / 1_000_000,
      maxMemoryUsageInMB: 192,
    });
    if (
      decoded.width !== inspected.width ||
      decoded.height !== inspected.height ||
      decoded.data.byteLength !== decoded.width * decoded.height * 3
    ) {
      throw new Error("Decoded JPEG metadata differs from its frame header");
    }
  } catch {
    throw new AssetImageError("INVALID_IMAGE");
  }
}

function fullyDecodePng(bytes: Uint8Array, inspected: InspectedImage): void {
  const png = inspected.png;
  if (!png) {
    throw new AssetImageError("INVALID_IMAGE");
  }

  const decodedBytes = inspected.width * inspected.height *
    Math.ceil(png.bitDepth / 8) * png.channels;
  const inflatedBytes = pngInflatedByteSize(
    inspected.width,
    inspected.height,
    png.bitDepth * png.channels,
    png.interlaced,
  );
  if (
    decodedBytes > MAX_PNG_DECODED_BYTES ||
    inflatedBytes > MAX_PNG_INFLATED_BYTES
  ) {
    throw new AssetImageError("IMAGE_DIMENSIONS_EXCEEDED");
  }

  try {
    verifyPngInflation(bytes, inflatedBytes);
    const decoded = decodePng(bytes, { checkCrc: true });
    if (
      decoded.width !== inspected.width ||
      decoded.height !== inspected.height ||
      decoded.data.byteLength === 0
    ) {
      throw new Error("Decoded PNG metadata differs from its IHDR");
    }
  } catch (error) {
    if (error instanceof AssetImageError) {
      throw error;
    }
    throw new AssetImageError("INVALID_IMAGE");
  }
}

async function fullyDecodeWebp(
  bytes: Uint8Array,
  inspected: InspectedImage,
): Promise<void> {
  await initializeWebpDecoder();

  try {
    const input = Uint8Array.from(bytes).buffer;
    const decoded = await decodeWebp(input);
    if (
      decoded.width !== inspected.width ||
      decoded.height !== inspected.height ||
      decoded.data.byteLength !== decoded.width * decoded.height * 4
    ) {
      throw new Error("Decoded WebP metadata differs from its container");
    }
  } catch {
    throw new AssetImageError("INVALID_IMAGE");
  }
}

function initializeWebpDecoder(): Promise<void> {
  webpInitialization ??= (async () => {
    try {
      const wasmBytes = await Deno.readFile(webpWasmUrl);
      const module = await WebAssembly.compile(wasmBytes);
      const initializeModule = initWebp as unknown as (
        module: WebAssembly.Module,
      ) => Promise<void>;
      await initializeModule(module);
    } catch {
      throw new Error("WebP decoder is unavailable.");
    }
  })();
  return webpInitialization;
}

function verifyPngInflation(bytes: Uint8Array, expectedBytes: number): void {
  let inflatedBytes = 0;
  const inflater = new Unzlib((chunk) => {
    inflatedBytes += chunk.byteLength;
    if (inflatedBytes > expectedBytes) {
      throw new AssetImageError("INVALID_IMAGE");
    }
  });

  let cursor: number = PNG_SIGNATURE.length;
  while (cursor < bytes.byteLength) {
    const length = readU32Be(bytes, cursor);
    const type = fourCc(bytes, cursor + 4);
    const dataStart = cursor + 8;
    if (type === "IDAT") {
      const dataEnd = dataStart + length;
      for (let offset = dataStart; offset < dataEnd; offset += 1024) {
        inflater.push(bytes.subarray(offset, Math.min(offset + 1024, dataEnd)));
      }
    } else if (type === "iCCP") {
      verifyPngProfile(bytes, dataStart, length);
    }
    cursor = dataStart + length + 4;
  }
  inflater.push(EMPTY_BYTES, true);
  if (inflatedBytes !== expectedBytes) {
    throw new AssetImageError("INVALID_IMAGE");
  }
}

function verifyPngProfile(
  bytes: Uint8Array,
  dataStart: number,
  length: number,
): void {
  const dataEnd = dataStart + length;
  let separator = dataStart;
  while (separator < dataEnd && bytes[separator] !== 0) {
    separator++;
  }
  const keywordLength = separator - dataStart;
  if (
    keywordLength === 0 ||
    keywordLength > 79 ||
    separator + 2 >= dataEnd ||
    bytes[separator + 1] !== 0
  ) {
    throw new AssetImageError("INVALID_IMAGE");
  }

  let profileBytes = 0;
  const inflater = new Unzlib((chunk) => {
    profileBytes += chunk.byteLength;
    if (profileBytes > MAX_PNG_PROFILE_BYTES) {
      throw new AssetImageError("INVALID_IMAGE");
    }
  });
  for (let offset = separator + 2; offset < dataEnd; offset += 1024) {
    inflater.push(bytes.subarray(offset, Math.min(offset + 1024, dataEnd)));
  }
  inflater.push(EMPTY_BYTES, true);
  if (profileBytes === 0) {
    throw new AssetImageError("INVALID_IMAGE");
  }
}

function pngInflatedByteSize(
  width: number,
  height: number,
  bitsPerPixel: number,
  interlaced: boolean,
): number {
  if (!interlaced) {
    return (Math.ceil(width * bitsPerPixel / 8) + 1) * height;
  }

  const passes = [
    [0, 0, 8, 8],
    [4, 0, 8, 8],
    [0, 4, 4, 8],
    [2, 0, 4, 4],
    [0, 2, 2, 4],
    [1, 0, 2, 2],
    [0, 1, 1, 2],
  ] as const;
  let total = 0;
  for (const [startX, startY, stepX, stepY] of passes) {
    if (width <= startX || height <= startY) {
      continue;
    }
    const passWidth = Math.ceil((width - startX) / stepX);
    const passHeight = Math.ceil((height - startY) / stepY);
    total += (Math.ceil(passWidth * bitsPerPixel / 8) + 1) * passHeight;
  }
  return total;
}

function pngChannelCount(colorType: number, bitDepth: number): number {
  const allowedDepths: Record<number, readonly number[]> = {
    0: [1, 2, 4, 8, 16],
    2: [8, 16],
    3: [1, 2, 4, 8],
    4: [8, 16],
    6: [8, 16],
  };
  if (!allowedDepths[colorType]?.includes(bitDepth)) {
    throw new AssetImageError("INVALID_IMAGE");
  }
  return ({ 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 } as Record<number, number>)[
    colorType
  ];
}

function inspectVp8Dimensions(
  bytes: Uint8Array,
  offset: number,
  length: number,
): { width: number; height: number } {
  if (
    length < 10 ||
    (bytes[offset] & 1) !== 0 ||
    bytes[offset + 3] !== 0x9d ||
    bytes[offset + 4] !== 0x01 ||
    bytes[offset + 5] !== 0x2a
  ) {
    throw new AssetImageError("INVALID_IMAGE");
  }
  const width = readU16Le(bytes, offset + 6) & 0x3fff;
  const height = readU16Le(bytes, offset + 8) & 0x3fff;
  if (width === 0 || height === 0) {
    throw new AssetImageError("INVALID_IMAGE");
  }
  return { width, height };
}

function inspectVp8lDimensions(
  bytes: Uint8Array,
  offset: number,
  length: number,
): { width: number; height: number } {
  if (length < 5 || bytes[offset] !== 0x2f) {
    throw new AssetImageError("INVALID_IMAGE");
  }
  return {
    width: 1 + bytes[offset + 1] + ((bytes[offset + 2] & 0x3f) << 8),
    height: 1 + (bytes[offset + 2] >> 6) +
      (bytes[offset + 3] << 2) + ((bytes[offset + 4] & 0x0f) << 10),
  };
}

function isStartOfFrame(marker: number): boolean {
  return marker >= 0xc0 && marker <= 0xcf &&
    marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc;
}

function exceedsPixelLimit(width: number, height: number): boolean {
  return width > Math.floor(MAX_IMAGE_PIXELS / height);
}

function hasPrefix(
  bytes: Uint8Array,
  prefix: readonly number[],
): boolean {
  return bytes.byteLength >= prefix.length &&
    prefix.every((value, index) => bytes[index] === value);
}

function fourCc(bytes: Uint8Array, offset: number): string {
  return String.fromCharCode(
    bytes[offset],
    bytes[offset + 1],
    bytes[offset + 2],
    bytes[offset + 3],
  );
}

function readU16Be(bytes: Uint8Array, offset: number): number {
  return (bytes[offset] << 8) | bytes[offset + 1];
}

function readU16Le(bytes: Uint8Array, offset: number): number {
  return bytes[offset] | (bytes[offset + 1] << 8);
}

function readU24Le(bytes: Uint8Array, offset: number): number {
  return bytes[offset] | (bytes[offset + 1] << 8) |
    (bytes[offset + 2] << 16);
}

function readU32Be(bytes: Uint8Array, offset: number): number {
  return (
    bytes[offset] * 0x1000000 +
    (bytes[offset + 1] << 16) +
    (bytes[offset + 2] << 8) +
    bytes[offset + 3]
  );
}

function readU32Le(bytes: Uint8Array, offset: number): number {
  return (
    bytes[offset] +
    (bytes[offset + 1] << 8) +
    (bytes[offset + 2] << 16) +
    bytes[offset + 3] * 0x1000000
  );
}
