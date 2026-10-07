import { setTimeout } from "node:timers/promises";

// CDN 构建仅发起 GET 请求；临时网络错误与 5xx 最多尝试三次。
export async function fetchWithRetry(url, options) {
  for (let attempt = 1; ; attempt++) {
    let response;
    try {
      response = await fetch(url, options);
    } catch (error) {
      if (!(error instanceof TypeError) || attempt === 3) {
        throw error;
      }
      console.warn(`Fetch attempt ${attempt}/3 failed: ${error.message}; retrying ${url}`);
    }

    if (response) {
      if (response.status < 500 || response.status > 599 || attempt === 3) {
        return response;
      }
      await response.body?.cancel();
      console.warn(`Fetch attempt ${attempt}/3 returned ${response.status}; retrying ${url}`);
    }

    await setTimeout(1000 * 2 ** (attempt - 1));
  }
}
