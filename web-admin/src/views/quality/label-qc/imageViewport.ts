export type ImageSize = {
  width: number;
  height: number;
};

export type ImagePan = {
  x: number;
  y: number;
};

const DEFAULT_IMAGE_SIZE: ImageSize = { width: 4, height: 3 };
const MIN_DIMENSION = 1;
const IDENTITY_EPSILON = 0.001;

function positiveDimension(value: number | null | undefined): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value > 0
    ? value
    : null;
}

export function resolveImageSize(
  reported: Partial<ImageSize>,
  natural?: Partial<ImageSize> | null,
): ImageSize {
  const reportedWidth = positiveDimension(reported.width);
  const reportedHeight = positiveDimension(reported.height);
  if (reportedWidth && reportedHeight) {
    return { width: reportedWidth, height: reportedHeight };
  }

  const naturalWidth = positiveDimension(natural?.width);
  const naturalHeight = positiveDimension(natural?.height);
  if (naturalWidth && naturalHeight) {
    return { width: naturalWidth, height: naturalHeight };
  }

  return DEFAULT_IMAGE_SIZE;
}

export function calculateImagePlaneStyle(
  image: ImageSize,
  viewport: ImageSize,
  zoom: number,
  pan: ImagePan,
): Record<string, string> {
  const safeImage = resolveImageSize(image);
  const viewportWidth = positiveDimension(viewport.width) ?? MIN_DIMENSION;
  const viewportHeight = positiveDimension(viewport.height) ?? MIN_DIMENSION;
  const imageRatio = safeImage.width / safeImage.height;
  const viewportRatio = viewportWidth / viewportHeight;

  let width = viewportWidth;
  let height = width / imageRatio;
  if (imageRatio < viewportRatio) {
    height = viewportHeight;
    width = height * imageRatio;
  }

  const style: Record<string, string> = {
    width: `${width}px`,
    height: `${height}px`,
    left: `${(viewportWidth - width) / 2}px`,
    top: `${(viewportHeight - height) / 2}px`,
  };

  const safeZoom = positiveDimension(zoom) ?? 1;
  const panX = Number.isFinite(pan.x) ? pan.x : 0;
  const panY = Number.isFinite(pan.y) ? pan.y : 0;
  const isIdentity = Math.abs(safeZoom - 1) < IDENTITY_EPSILON
    && Math.abs(panX) < IDENTITY_EPSILON
    && Math.abs(panY) < IDENTITY_EPSILON;

  // Large camera photos are kept out of a GPU compositing layer at rest.
  // Some older Windows/browser combinations can paint the thumbnail but drop
  // the same full-size texture when an identity transform is always present.
  if (!isIdentity) {
    style.transform = `translate(${panX}px, ${panY}px) scale(${safeZoom})`;
  }

  return style;
}
