export interface ModelCapability {
  id: string;
  name: string;
  provider: string;
  modality: 'IMAGE' | 'VIDEO';
  supportedRatios: Array<{ label: string; value: string }>;
  supportedDurations?: string[];
  supportedModes?: string[];
  supportsReferenceImage: boolean;
  supportsNegativePrompt: boolean;
  supportsSeed: boolean;
  estimatedCostCents: number;
  badge?: string;
}

export const MODEL_CAPABILITIES: Record<string, ModelCapability> = {
  'flux-1.1-pro': {
    id: 'flux-1.1-pro',
    name: 'FLUX 1.1 Pro',
    provider: 'Black Forest Labs',
    modality: 'IMAGE',
    supportedRatios: [
      { label: '1:1 Square', value: '1:1' },
      { label: '16:9 Landscape', value: '16:9' },
      { label: '9:16 Portrait', value: '9:16' },
      { label: '4:3 Standard', value: '4:3' },
      { label: '3:4 Vertical', value: '3:4' },
    ],
    supportsReferenceImage: true,
    supportsNegativePrompt: false,
    supportsSeed: true,
    estimatedCostCents: 4,
    badge: 'Fast & Photoreal',
  },
  'dall-e-3': {
    id: 'dall-e-3',
    name: 'DALL-E 3',
    provider: 'OpenAI',
    modality: 'IMAGE',
    supportedRatios: [
      { label: '1:1 Square', value: '1:1' },
      { label: '16:9 Landscape', value: '16:9' },
      { label: '9:16 Portrait', value: '9:16' },
    ],
    supportsReferenceImage: false,
    supportsNegativePrompt: false,
    supportsSeed: false,
    estimatedCostCents: 8,
    badge: 'High Adherence',
  },
  'recraft-v3': {
    id: 'recraft-v3',
    name: 'Recraft v3',
    provider: 'Recraft',
    modality: 'IMAGE',
    supportedRatios: [
      { label: '1:1 Square', value: '1:1' },
      { label: '16:9 Landscape', value: '16:9' },
      { label: '9:16 Portrait', value: '9:16' },
      { label: '4:3 Standard', value: '4:3' },
    ],
    supportsReferenceImage: true,
    supportsNegativePrompt: true,
    supportsSeed: true,
    estimatedCostCents: 3.5,
    badge: 'Vector & UI',
  },
  'runway-gen3-alpha': {
    id: 'runway-gen3-alpha',
    name: 'Gen-3 Alpha',
    provider: 'Runway',
    modality: 'VIDEO',
    supportedRatios: [
      { label: '16:9 Landscape', value: '16:9' },
      { label: '9:16 Vertical', value: '9:16' },
    ],
    supportedDurations: ['5s', '10s'],
    supportedModes: ['720p HD', '1080p Full HD'],
    supportsReferenceImage: true,
    supportsNegativePrompt: false,
    supportsSeed: true,
    estimatedCostCents: 50,
    badge: 'Cinematic Motion',
  },
  'kling-v1.5': {
    id: 'kling-v1.5',
    name: 'Kling 1.5',
    provider: 'Kuaishou',
    modality: 'VIDEO',
    supportedRatios: [
      { label: '16:9 Landscape', value: '16:9' },
      { label: '9:16 Vertical', value: '9:16' },
      { label: '1:1 Square', value: '1:1' },
    ],
    supportedDurations: ['5s', '10s'],
    supportedModes: ['Standard', 'Professional'],
    supportsReferenceImage: true,
    supportsNegativePrompt: true,
    supportsSeed: false,
    estimatedCostCents: 40,
    badge: 'Physics & Action',
  },
  'luma-dream-machine': {
    id: 'luma-dream-machine',
    name: 'Dream Machine',
    provider: 'Luma AI',
    modality: 'VIDEO',
    supportedRatios: [
      { label: '16:9 Landscape', value: '16:9' },
      { label: '9:16 Vertical', value: '9:16' },
      { label: '4:3 Standard', value: '4:3' },
      { label: '21:9 Ultrawide', value: '21:9' },
    ],
    supportedDurations: ['5s'],
    supportedModes: ['Smooth Motion'],
    supportsReferenceImage: true,
    supportsNegativePrompt: false,
    supportsSeed: false,
    estimatedCostCents: 60,
    badge: 'Camera Tracking',
  },
};

export interface PromptTemplate {
  id: string;
  category: 'GAME' | 'PHOTO' | 'VIDEO' | 'ART';
  title: string;
  badge: string;
  prompt: string;
  negativePrompt?: string;
  recommendedModel: string;
  recommendedRatio: string;
  recommendedDuration?: string;
  thumbnailUrl: string;
  tags: string[];
}

export const PROMPT_TEMPLATES: PromptTemplate[] = [
  {
    id: 'pixel-sprite',
    category: 'GAME',
    title: '16-Bit Pixel Hero',
    badge: 'Game Sprite',
    prompt: 'Detailed 16-bit pixel art sprite sheet of a cyber warrior character in action pose, clean outlines, sharp palette, white background, retro game asset',
    negativePrompt: 'blurry, 3d, realistic, smooth, gradients, shadows',
    recommendedModel: 'flux-1.1-pro',
    recommendedRatio: '1:1',
    thumbnailUrl: 'https://images.unsplash.com/photo-1550745165-9bc0b252726f?auto=format&fit=crop&w=600&q=80',
    tags: ['pixel-art', 'character', 'retro', 'game-asset'],
  },
  {
    id: 'isometric-dungeon',
    category: 'GAME',
    title: 'Isometric Dungeon Tile',
    badge: '3D Diorama',
    prompt: 'Isometric 3D render of a glowing crystal cave dungeon tile with stone floor and treasure chest, Unreal Engine 5 aesthetic, volumetric lighting, diorama cutout',
    recommendedModel: 'flux-1.1-pro',
    recommendedRatio: '1:1',
    thumbnailUrl: 'https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?auto=format&fit=crop&w=600&q=80',
    tags: ['isometric', 'dungeon', 'ue5', 'environment'],
  },
  {
    id: 'fantasy-spell-icon',
    category: 'GAME',
    title: 'Spell Skill Icon',
    badge: 'UI Asset',
    prompt: 'Fantasy RPG magic spell icon depicting a glowing vortex fireball, dark ornate metallic border, high contrast, glossy vector game UI art, centered',
    recommendedModel: 'recraft-v3',
    recommendedRatio: '1:1',
    thumbnailUrl: 'https://images.unsplash.com/photo-1579783900882-c0d3dad7b119?auto=format&fit=crop&w=600&q=80',
    tags: ['icon', 'magic', 'rpg', 'ui'],
  },
  {
    id: 'cinematic-portrait',
    category: 'PHOTO',
    title: '35mm Film Portrait',
    badge: 'Photoreal',
    prompt: 'Candid intimate portrait of an architect in an open concrete studio, natural golden hour sidelight, Kodak Portra 400 film grain, 35mm lens, f/1.8 shallow depth of field',
    recommendedModel: 'flux-1.1-pro',
    recommendedRatio: '4:3',
    thumbnailUrl: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=600&q=80',
    tags: ['portrait', '35mm', 'analog', 'golden-hour'],
  },
  {
    id: 'cyberpunk-drone-flight',
    category: 'VIDEO',
    title: 'Cyberpunk Neon Flythrough',
    badge: 'Cinematic FPV',
    prompt: 'Cinematic fast-moving FPV drone flight navigating through rainy narrow alleys of a dense neon-lit cyberpunk metropolis, reflections on wet asphalt, steam vents, 4k 60fps',
    recommendedModel: 'runway-gen3-alpha',
    recommendedRatio: '16:9',
    recommendedDuration: '5s',
    thumbnailUrl: 'https://images.unsplash.com/photo-1518709268805-4e9042af9f23?auto=format&fit=crop&w=600&q=80',
    tags: ['cyberpunk', 'drone', 'fpv', 'neon', 'cinematic'],
  },
  {
    id: 'morphing-fluid-macro',
    category: 'VIDEO',
    title: 'Chromatic Swirl Macro',
    badge: 'Abstract Video',
    prompt: 'Hypnotic slow-motion macro shot of colorful iridescent fluid dyes expanding and colliding in water, micro bubbles, ethereal studio lighting, fluid dynamics simulation',
    recommendedModel: 'luma-dream-machine',
    recommendedRatio: '16:9',
    recommendedDuration: '5s',
    thumbnailUrl: 'https://images.unsplash.com/photo-1541701494587-cb58502866ab?auto=format&fit=crop&w=600&q=80',
    tags: ['macro', 'fluid', 'abstract', 'colors'],
  },
  {
    id: 'japanese-ink-landscape',
    category: 'ART',
    title: 'Sumi-e Ink Painting',
    badge: 'Traditional Art',
    prompt: 'Traditional Japanese sumi-e ink wash painting of misty mountains and solitary pine tree, minimalist brush strokes, rice paper texture, contemplative atmosphere',
    recommendedModel: 'flux-1.1-pro',
    recommendedRatio: '16:9',
    thumbnailUrl: 'https://images.unsplash.com/photo-1579783902614-a3fb3927b675?auto=format&fit=crop&w=600&q=80',
    tags: ['sumi-e', 'ink', 'japan', 'minimalist'],
  },
];

export interface GalleryItem {
  id: string;
  title: string;
  prompt: string;
  negativePrompt?: string;
  author: string;
  authorAvatar?: string;
  modality: 'IMAGE' | 'VIDEO';
  model: string;
  provider: string;
  aspectRatio: string;
  duration?: string;
  seed?: number;
  outputUrl: string;
  thumbnailUrl: string;
  likes: number;
  remixes: number;
  isOfficial?: boolean;
  category: 'GAME' | 'PHOTO' | 'VIDEO' | 'ART';
  tags: string[];
  createdAt: string;
  forkedFromTitle?: string;
}

export const INITIAL_GALLERY_ITEMS: GalleryItem[] = [
  {
    id: 'gal-1',
    title: 'Cyberpunk Neon Alleyway',
    prompt: 'Cinematic fast-moving FPV drone flight navigating through rainy narrow alleys of a dense neon-lit cyberpunk metropolis, reflections on wet asphalt',
    author: 'Godance Studio',
    modality: 'VIDEO',
    model: 'runway-gen3-alpha',
    provider: 'Runway',
    aspectRatio: '16:9',
    duration: '5s',
    outputUrl: 'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4',
    thumbnailUrl: 'https://images.unsplash.com/photo-1518709268805-4e9042af9f23?auto=format&fit=crop&w=800&q=80',
    likes: 142,
    remixes: 39,
    isOfficial: true,
    category: 'VIDEO',
    tags: ['cyberpunk', 'neon', 'cinematic'],
    createdAt: '2 hours ago',
  },
  {
    id: 'gal-2',
    title: '16-Bit Cyber Warrior Sprite',
    prompt: 'Detailed 16-bit pixel art sprite sheet of a cyber warrior character in action pose, clean outlines, sharp palette, white background, retro game asset',
    negativePrompt: 'blurry, 3d, realistic',
    author: 'alex_indie',
    modality: 'IMAGE',
    model: 'flux-1.1-pro',
    provider: 'Black Forest Labs',
    aspectRatio: '1:1',
    seed: 482910,
    outputUrl: 'https://images.unsplash.com/photo-1550745165-9bc0b252726f?auto=format&fit=crop&w=1200&q=80',
    thumbnailUrl: 'https://images.unsplash.com/photo-1550745165-9bc0b252726f?auto=format&fit=crop&w=800&q=80',
    likes: 98,
    remixes: 27,
    isOfficial: false,
    category: 'GAME',
    tags: ['pixel-art', 'game-dev', 'sprite'],
    createdAt: '4 hours ago',
    forkedFromTitle: '16-Bit Pixel Hero',
  },
  {
    id: 'gal-3',
    title: 'Studio Film Portrait in Portra 400',
    prompt: 'Candid intimate portrait of an architect in an open concrete studio, natural golden hour sidelight, Kodak Portra 400 film grain, 35mm lens, f/1.8 shallow depth of field',
    author: 'elena_lens',
    modality: 'IMAGE',
    model: 'flux-1.1-pro',
    provider: 'Black Forest Labs',
    aspectRatio: '4:3',
    seed: 12904,
    outputUrl: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=1200&q=80',
    thumbnailUrl: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=800&q=80',
    likes: 215,
    remixes: 53,
    isOfficial: false,
    category: 'PHOTO',
    tags: ['portrait', 'portra400', 'film'],
    createdAt: '6 hours ago',
    forkedFromTitle: '35mm Film Portrait',
  },
  {
    id: 'gal-4',
    title: 'Isometric Crystal Dungeon Tile',
    prompt: 'Isometric 3D render of a glowing crystal cave dungeon tile with stone floor and treasure chest, Unreal Engine 5 aesthetic, volumetric lighting, diorama cutout',
    author: 'Godance Studio',
    modality: 'IMAGE',
    model: 'flux-1.1-pro',
    provider: 'Black Forest Labs',
    aspectRatio: '1:1',
    seed: 90214,
    outputUrl: 'https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?auto=format&fit=crop&w=1200&q=80',
    thumbnailUrl: 'https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?auto=format&fit=crop&w=800&q=80',
    likes: 83,
    remixes: 19,
    isOfficial: true,
    category: 'GAME',
    tags: ['isometric', 'ue5', 'dungeon'],
    createdAt: '12 hours ago',
  },
  {
    id: 'gal-5',
    title: 'Vortex Fireball Spell UI Icon',
    prompt: 'Fantasy RPG magic spell icon depicting a glowing vortex fireball, dark ornate metallic border, high contrast, glossy vector game UI art, centered',
    author: 'rpg_craft',
    modality: 'IMAGE',
    model: 'recraft-v3',
    provider: 'Recraft',
    aspectRatio: '1:1',
    seed: 7721,
    outputUrl: 'https://images.unsplash.com/photo-1579783900882-c0d3dad7b119?auto=format&fit=crop&w=1200&q=80',
    thumbnailUrl: 'https://images.unsplash.com/photo-1579783900882-c0d3dad7b119?auto=format&fit=crop&w=800&q=80',
    likes: 67,
    remixes: 14,
    isOfficial: false,
    category: 'GAME',
    tags: ['icon', 'ui', 'magic'],
    createdAt: '1 day ago',
    forkedFromTitle: 'Spell Skill Icon',
  },
  {
    id: 'gal-6',
    title: 'Chromatic Liquid Swirl Macro',
    prompt: 'Hypnotic slow-motion macro shot of colorful iridescent fluid dyes expanding and colliding in water, micro bubbles, ethereal studio lighting, fluid dynamics simulation',
    author: 'motion_mind',
    modality: 'VIDEO',
    model: 'luma-dream-machine',
    provider: 'Luma AI',
    aspectRatio: '16:9',
    duration: '5s',
    outputUrl: 'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4',
    thumbnailUrl: 'https://images.unsplash.com/photo-1541701494587-cb58502866ab?auto=format&fit=crop&w=800&q=80',
    likes: 176,
    remixes: 44,
    isOfficial: false,
    category: 'VIDEO',
    tags: ['fluid', 'macro', 'colors'],
    createdAt: '1 day ago',
  },
];
