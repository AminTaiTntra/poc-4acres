import fs from 'fs'
import ee from '@google/earthengine'

let readyPromise = null

/**
 * Authenticates against Earth Engine using the service account key already
 * provisioned for this project (GEE_SERVICE_ACCOUNT_JSON / GEE_PROJECT_ID),
 * and caches the initialization promise so every API route reuses one
 * session instead of re-authenticating per request.
 */
function initEarthEngine() {
  if (readyPromise) return readyPromise

  const keyPath = process.env.GEE_SERVICE_ACCOUNT_JSON
  const projectId = process.env.GEE_PROJECT_ID

  if (!keyPath) {
    return Promise.reject(new Error('GEE_SERVICE_ACCOUNT_JSON is not set'))
  }

  const privateKey = JSON.parse(fs.readFileSync(keyPath, 'utf8'))

  readyPromise = new Promise((resolve, reject) => {
    ee.data.authenticateViaPrivateKey(
      privateKey,
      () => {
        if (projectId && typeof ee.data.setProject === 'function') {
          ee.data.setProject(projectId)
        }
        ee.initialize(
          null,
          null,
          () => resolve(ee),
          (err) => reject(new Error(`ee.initialize failed: ${err}`))
        )
      },
      (err) => reject(new Error(`Earth Engine auth failed: ${err}`))
    )
  })

  return readyPromise
}

export { ee, initEarthEngine }
