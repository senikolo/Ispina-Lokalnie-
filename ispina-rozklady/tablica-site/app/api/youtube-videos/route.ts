import {youtubeResponse} from '../../../lib/youtube.mjs';

export const dynamic = 'force-dynamic';
export const maxDuration = 30;

export async function GET(request: Request) {
  return youtubeResponse(request);
}
