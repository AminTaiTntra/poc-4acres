#!/usr/bin/env node
// Run once: node scripts/register-gfw-geostores.js
// Requires: GFW_API_KEY env var, Node 18+

const GFW_API_KEY = process.env.GFW_API_KEY;
if (!GFW_API_KEY) { console.error('GFW_API_KEY required'); process.exit(1); }

const patches = [
  { name: 'Amazon Várzea Forest',        coords: [[-62.2165706,-3.4658705],[-62.2152294,-3.4658705],[-62.2152294,-3.4647295],[-62.2165706,-3.4647295],[-62.2165706,-3.4658705]] },
  { name: 'Sundarbans Mangrove',          coords: [[89.1826852,21.9491295],[89.1839148,21.9491295],[89.1839148,21.9502705],[89.1826852,21.9502705],[89.1826852,21.9491295]] },
  { name: 'Maasai Mara Savanna',          coords: [[35.1035694,-1.5447705],[35.1048306,-1.5447705],[35.1048306,-1.5436295],[35.1035694,-1.5436295],[35.1035694,-1.5447705]] },
  { name: 'Cairngorms Highland',          coords: [[-3.8951537,57.1224295],[-3.8928463,57.1224295],[-3.8928463,57.1235705],[-3.8951537,57.1235705],[-3.8951537,57.1224295]] },
  { name: 'Borneo Rainforest',            coords: [[113.9937696,2.1890295],[113.9950304,2.1890295],[113.9950304,2.1901705],[113.9937696,2.1901705],[113.9937696,2.1890295]] },
  { name: 'Daintree Rainforest',          coords: [[145.4193076,-16.1705705],[145.4206924,-16.1705705],[145.4206924,-16.1694295],[145.4193076,-16.1694295],[145.4193076,-16.1705705]] },
  { name: 'Yellowstone Forest',           coords: [[-110.5893005,44.4274295],[-110.5876995,44.4274295],[-110.5876995,44.4285705],[-110.5893005,44.4285705],[-110.5893005,44.4274295]] },
  { name: 'Sahel Dryland',               coords: [[-5.0005912,14.8827295],[-4.9994088,14.8827295],[-4.9994088,14.8838705],[-5.0005912,14.8838705],[-5.0005912,14.8827295]] },
  { name: 'Białowieża Primeval Forest',   coords: [[23.8594139,52.7063295],[23.8607861,52.7063295],[23.8607861,52.7074705],[23.8594139,52.7074705],[23.8594139,52.7063295]] },
  { name: 'Patagonian Steppe',           coords: [[-72.2668902,-50.3503705],[-72.2651098,-50.3503705],[-72.2651098,-50.3492295],[-72.2668902,-50.3492295],[-72.2668902,-50.3503705]] },
];

async function register(patch) {
  const body = {
    geojson: {
      type: 'FeatureCollection',
      features: [{
        type: 'Feature',
        geometry: { type: 'Polygon', coordinates: [patch.coords] },
        properties: {}
      }]
    }
  };
  const res = await fetch('https://data-api.globalforestwatch.org/dataset/geostore', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'x-api-key': GFW_API_KEY,
    },
    body: JSON.stringify(body),
  });
  const json = await res.json();
  if (!res.ok) throw new Error(`GFW error for ${patch.name}: ${JSON.stringify(json)}`);
  return json.data?.id || json.geostore_id;
}

(async () => {
  console.log('-- Run these SQL updates after registration:');
  for (const patch of patches) {
    try {
      const id = await register(patch);
      console.log(`UPDATE patches SET gfw_geostore_id = '${id}' WHERE name = '${patch.name}';`);
    } catch (e) {
      console.error(e.message);
    }
  }
})();
