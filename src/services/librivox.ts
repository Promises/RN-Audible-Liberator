/**
 * LibriVox API Client
 *
 * Pure TypeScript client for the LibriVox public domain audiobook API.
 * No authentication required.
 *
 * API docs: https://librivox.org/api/info
 */

const API_BASE = 'https://librivox.org/api/feed';
const REQUEST_TIMEOUT = 15000;

// ============================================================================
// Types
// ============================================================================

export interface LibriVoxAuthor {
  id: string;
  first_name: string;
  last_name: string;
}

export interface LibriVoxBook {
  id: string;
  title: string;
  description: string;
  url_librivox: string;
  url_other: string;
  url_project: string;
  url_rss: string;
  url_zip_file: string;
  language: string;
  copyright_year: string;
  num_sections: string;
  totaltimesecs: number;
  authors: LibriVoxAuthor[];
}

export interface LibriVoxSection {
  id: string;
  section_number: string;
  title: string;
  listen_url: string;
  language: string;
  playtime: string;
  file_name: string;
  readers: { display_name: string }[];
}

interface LibriVoxApiResponse<T> {
  books?: T[];
  sections?: T[];
  error?: string;
}

// ============================================================================
// API Functions
// ============================================================================

async function fetchWithTimeout(url: string): Promise<Response> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT);

  try {
    const response = await fetch(url, { signal: controller.signal });
    if (!response.ok) {
      throw new Error(`LibriVox API error: ${response.status}`);
    }
    return response;
  } finally {
    clearTimeout(timeout);
  }
}

/**
 * Search LibriVox audiobooks by title or author.
 */
export async function searchBooks(
  query: string,
  offset: number = 0,
  limit: number = 20
): Promise<LibriVoxBook[]> {
  if (!query.trim()) return [];

  const params = new URLSearchParams({
    title: `^${query}`,
    format: 'json',
    limit: String(limit),
    offset: String(offset),
    fields: '{id,title,description,url_librivox,url_other,url_zip_file,language,copyright_year,num_sections,totaltimesecs,authors}',
  });

  try {
    const response = await fetchWithTimeout(`${API_BASE}/audiobooks?${params}`);
    const data: LibriVoxApiResponse<LibriVoxBook> = await response.json();
    return data.books || [];
  } catch (error: any) {
    if (error.name === 'AbortError') {
      console.warn('[LibriVox] Search request timed out');
      return [];
    }
    console.error('[LibriVox] Search error:', error);
    return [];
  }
}

/**
 * Search LibriVox audiobooks by author name.
 */
export async function searchByAuthor(
  author: string,
  offset: number = 0,
  limit: number = 20
): Promise<LibriVoxBook[]> {
  if (!author.trim()) return [];

  const params = new URLSearchParams({
    author: `^${author}`,
    format: 'json',
    limit: String(limit),
    offset: String(offset),
    fields: '{id,title,description,url_librivox,url_other,url_zip_file,language,copyright_year,num_sections,totaltimesecs,authors}',
  });

  try {
    const response = await fetchWithTimeout(`${API_BASE}/audiobooks?${params}`);
    const data: LibriVoxApiResponse<LibriVoxBook> = await response.json();
    return data.books || [];
  } catch (error: any) {
    if (error.name === 'AbortError') return [];
    console.error('[LibriVox] Author search error:', error);
    return [];
  }
}

/**
 * Get recent/popular LibriVox audiobooks for browsing.
 */
export async function getRecentBooks(
  offset: number = 0,
  limit: number = 20
): Promise<LibriVoxBook[]> {
  const params = new URLSearchParams({
    format: 'json',
    limit: String(limit),
    offset: String(offset),
    fields: '{id,title,description,url_librivox,url_other,url_zip_file,language,copyright_year,num_sections,totaltimesecs,authors}',
  });

  try {
    const response = await fetchWithTimeout(`${API_BASE}/audiobooks?${params}`);
    const data: LibriVoxApiResponse<LibriVoxBook> = await response.json();
    return data.books || [];
  } catch (error: any) {
    if (error.name === 'AbortError') return [];
    console.error('[LibriVox] Recent books error:', error);
    return [];
  }
}

/**
 * Get a single LibriVox audiobook by ID.
 */
export async function getBook(bookId: string): Promise<LibriVoxBook | null> {
  const params = new URLSearchParams({
    id: bookId,
    format: 'json',
    fields: '{id,title,description,url_librivox,url_other,url_zip_file,language,copyright_year,num_sections,totaltimesecs,authors}',
  });

  try {
    const response = await fetchWithTimeout(`${API_BASE}/audiobooks?${params}`);
    const data: LibriVoxApiResponse<LibriVoxBook> = await response.json();
    return data.books?.[0] ?? null;
  } catch (error: any) {
    console.error('[LibriVox] getBook error:', error);
    return null;
  }
}

/**
 * Get chapters/sections for a specific book.
 */
export async function getBookSections(bookId: string): Promise<LibriVoxSection[]> {
  const params = new URLSearchParams({
    project_id: bookId,
    format: 'json',
  });

  try {
    const response = await fetchWithTimeout(`${API_BASE}/audiotracks?${params}`);
    const data: LibriVoxApiResponse<LibriVoxSection> = await response.json();
    return data.sections || [];
  } catch (error: any) {
    if (error.name === 'AbortError') return [];
    console.error('[LibriVox] Sections error:', error);
    return [];
  }
}

// ============================================================================
// Helpers
// ============================================================================

/**
 * Get the full author name from a LibriVox author object.
 */
export function getAuthorName(author: LibriVoxAuthor): string {
  return `${author.first_name} ${author.last_name}`.trim();
}

/**
 * Get a cover image URL for a LibriVox book.
 * LibriVox doesn't serve covers directly, but Archive.org often has them.
 */
export function getCoverUrl(book: LibriVoxBook): string | null {
  if (!book.url_librivox) return null;
  // Extract the slug from the LibriVox URL
  // e.g., "https://librivox.org/pride-and-prejudice-by-jane-austen/" -> "pride-and-prejudice-by-jane-austen"
  const match = book.url_librivox.match(/librivox\.org\/([^/]+)\/?$/);
  if (!match) return null;
  return `https://archive.org/services/img/${match[1]}`;
}

/**
 * Format total seconds into a human-readable duration.
 */
export function formatDuration(totalSeconds: number): string {
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  if (hours > 0) {
    return `${hours}h ${minutes}m`;
  }
  return `${minutes}m`;
}

/**
 * Convert a playtime string (HH:MM:SS) to total seconds.
 */
export function playtimeToSeconds(playtime: string): number {
  const parts = playtime.split(':').map(Number);
  if (parts.length === 3) {
    return parts[0] * 3600 + parts[1] * 60 + parts[2];
  }
  if (parts.length === 2) {
    return parts[0] * 60 + parts[1];
  }
  return 0;
}
