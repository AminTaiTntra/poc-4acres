process.env.GEE_SERVICE_ACCOUNT_JSON = '/home/tntra/Desktop/Projects/poc-4acres/secrets/ee-4acres-a7c0716b40c0.json';
process.env.GEE_PROJECT_ID = 'ee-4acres';
const { getWaterData } = await import('./app/lib/geoEngine/water.js');

const patches = [
  ['Amazon Várzea Forest', -3.4653, -62.2159],
  ['Sundarbans Mangrove', 21.9497, 89.1833],
  ['Maasai Mara Savanna', -1.5442, 35.1042],
  ['Cairngorms Highland', 57.1230, -3.8940],
  ['Borneo Rainforest', 2.1896, 113.9944],
  ['Daintree Rainforest', -16.1700, 145.4200],
  ['Yellowstone Forest', 44.4280, -110.5885],
  ['Sahel Dryland', 14.8833, -5.0000],
  ['Białowieża Primeval Forest', 52.7069, 23.8601],
  ['Patagonian Steppe', -50.3498, -72.2660],
];

for (const [name, lat, lng] of patches) {
  const r = await getWaterData(lat, lng, 71.8);
  console.log(name.padEnd(28), JSON.stringify(r));
}
