/**
 * The Earth Engine Node client is callback-based (a computation graph is only
 * evaluated server-side when you call .evaluate()) — this wraps that in a
 * Promise so the API routes can just await it.
 */
export function evaluate(eeObject) {
  return new Promise((resolve, reject) => {
    eeObject.evaluate((result, error) => {
      if (error) reject(new Error(error))
      else resolve(result)
    })
  })
}
