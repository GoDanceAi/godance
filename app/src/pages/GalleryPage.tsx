import { useEffect, useMemo, useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { apiFetch, type GalleryListResponse } from '../lib/api';
import {
  INITIAL_GALLERY_ITEMS,
  type GalleryItem,
} from '../lib/mediaCapabilities';

export default function GalleryPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<GalleryItem[]>(INITIAL_GALLERY_ITEMS);
  const [searchQuery, setSearchQuery] = useState('');
  const [sourceFilter, setSourceFilter] = useState<'ALL' | 'OFFICIAL' | 'COMMUNITY'>('ALL');
  const [modalityFilter, setModalityFilter] = useState<'ALL' | 'IMAGE' | 'VIDEO'>('ALL');
  const [categoryFilter, setCategoryFilter] = useState<'ALL' | 'GAME' | 'PHOTO' | 'VIDEO' | 'ART'>('ALL');
  const [modelFilter, setModelFilter] = useState<string>('ALL');

  // Lightbox Detail Modal
  const [activeItem, setActiveItem] = useState<GalleryItem | null>(null);
  const [copyFeedback, setCopyFeedback] = useState(false);

  useEffect(() => {
    let cancelled = false;
    apiFetch<GalleryListResponse>('/v1/gallery', { auth: false })
      .then((res) => {
        if (cancelled || !res.items || res.items.length === 0) return;
        const mapped: GalleryItem[] = res.items.map((dto) => ({
          id: dto.id,
          title: dto.title,
          prompt: dto.prompt,
          negativePrompt: dto.negativePrompt || undefined,
          author: dto.authorName,
          modality: dto.modality,
          model: dto.model,
          provider: dto.provider,
          aspectRatio: dto.aspectRatio,
          duration: dto.duration || undefined,
          seed: dto.seed || undefined,
          outputUrl: dto.outputUrl,
          thumbnailUrl: dto.thumbnailUrl || dto.outputUrl,
          likes: dto.likesCount,
          remixes: dto.remixCount,
          isOfficial: dto.isOfficial,
          category: (dto.category as GalleryItem['category']) || 'ART',
          tags: dto.tags || [],
          forkedFromTitle: dto.forkedFromTitle || undefined,
          createdAt: new Date(dto.createdAt).toLocaleDateString(),
        }));
        setItems(mapped);
      })
      .catch(() => {
        // Fall back to INITIAL_GALLERY_ITEMS if backend offline
      });
    return () => { cancelled = true; };
  }, []);

  const handleLike = (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setItems((prev) =>
      prev.map((item) =>
        item.id === id ? { ...item, likes: item.likes + 1 } : item,
      ),
    );
    if (activeItem?.id === id) {
      setActiveItem((prev) => (prev ? { ...prev, likes: prev.likes + 1 } : null));
    }
    void apiFetch(`/v1/gallery/${id}/like`, { method: 'POST', auth: false }).catch(() => {});
  };

  const handleRemix = (item: GalleryItem, e?: React.MouseEvent) => {
    if (e) e.stopPropagation();
    const params = new URLSearchParams({
      prompt: item.prompt,
      model: item.model,
      kind: item.modality,
      ratio: item.aspectRatio,
      forkedFrom: item.title,
    });
    if (item.duration) {
      params.set('duration', item.duration);
    }
    navigate(`/generate?${params.toString()}`);
  };

  const handleCopyPrompt = (promptText: string) => {
    void navigator.clipboard.writeText(promptText);
    setCopyFeedback(true);
    setTimeout(() => setCopyFeedback(false), 2000);
  };

  const filteredItems = useMemo(() => {
    return items.filter((item) => {
      const matchSource =
        sourceFilter === 'ALL' ||
        (sourceFilter === 'OFFICIAL' && item.isOfficial) ||
        (sourceFilter === 'COMMUNITY' && !item.isOfficial);

      const matchModality =
        modalityFilter === 'ALL' || item.modality === modalityFilter;

      const matchCategory =
        categoryFilter === 'ALL' || item.category === categoryFilter;

      const matchModel =
        modelFilter === 'ALL' || item.model === modelFilter;

      const q = searchQuery.toLowerCase().trim();
      const matchSearch =
        !q ||
        item.title.toLowerCase().includes(q) ||
        item.prompt.toLowerCase().includes(q) ||
        item.author.toLowerCase().includes(q) ||
        item.tags.some((t) => t.toLowerCase().includes(q));

      return matchSource && matchModality && matchCategory && matchModel && matchSearch;
    });
  }, [items, sourceFilter, modalityFilter, categoryFilter, modelFilter, searchQuery]);

  return (
    <div className="mx-auto max-w-7xl px-4 py-8 sm:px-6 lg:px-8">
      {/* Page Header */}
      <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
        <div>
          <div className="inline-flex items-center gap-2">
            <span className="pill pill-violet font-mono text-[11px]">GALLERY</span>
            <span className="text-xs font-semibold uppercase tracking-wider text-fg-subtle">
              Community & Recipe Showcase
            </span>
          </div>
          <h1 className="mt-1 text-2xl font-bold tracking-tight text-fg sm:text-3xl">
            Prompt Gallery & Creations
          </h1>
          <p className="mt-0.5 text-xs text-fg-muted">
            Explore verified recipes and creator outputs. Click any creation to inspect prompts or remix directly in the playground.
          </p>
        </div>

        <div>
          <Link to="/generate" className="btn btn-primary btn-sm flex items-center gap-1.5 shadow-sm">
            <span>✨ Open Studio</span>
            <span>→</span>
          </Link>
        </div>
      </div>

      {/* Filter & Search Bar */}
      <div className="mt-6 space-y-3 rounded-lg border border-border bg-surface p-4 shadow-sm">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          {/* Simple Search Input */}
          <div className="relative flex-1">
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search prompts, authors, styles (e.g. pixel, drone, sumi-e)..."
              className="input pl-8 text-xs"
            />
            <span className="absolute left-2.5 top-2 text-xs text-fg-faint">🔍</span>
          </div>

          {/* Model Engine Filter */}
          <div className="flex items-center gap-2">
            <label htmlFor="gallery-model-filter" className="text-xs font-semibold text-fg-subtle">
              Engine:
            </label>
            <select
              id="gallery-model-filter"
              value={modelFilter}
              onChange={(e) => setModelFilter(e.target.value)}
              className="input py-1.5 text-xs"
            >
              <option value="ALL">All Models</option>
              <option value="flux-1.1-pro">FLUX 1.1 Pro</option>
              <option value="recraft-v3">Recraft v3</option>
              <option value="runway-gen3-alpha">Runway Gen-3</option>
              <option value="luma-dream-machine">Luma Dream Machine</option>
            </select>
          </div>
        </div>

        {/* Filter Badges & Categories */}
        <div className="flex flex-wrap items-center justify-between gap-2 border-t border-border pt-3">
          {/* Source Tabs */}
          <div className="flex rounded-md bg-bg-muted p-1">
            <button
              type="button"
              onClick={() => setSourceFilter('ALL')}
              className={`rounded px-2.5 py-1 text-xs font-semibold transition-all ${
                sourceFilter === 'ALL'
                  ? 'bg-surface text-fg shadow-sm'
                  : 'text-fg-muted hover:text-fg'
              }`}
            >
              All Assets ({items.length})
            </button>
            <button
              type="button"
              onClick={() => setSourceFilter('OFFICIAL')}
              className={`rounded px-2.5 py-1 text-xs font-semibold transition-all ${
                sourceFilter === 'OFFICIAL'
                  ? 'bg-surface text-fg shadow-sm'
                  : 'text-fg-muted hover:text-fg'
              }`}
            >
              ⭐ Official Recipes
            </button>
            <button
              type="button"
              onClick={() => setSourceFilter('COMMUNITY')}
              className={`rounded px-2.5 py-1 text-xs font-semibold transition-all ${
                sourceFilter === 'COMMUNITY'
                  ? 'bg-surface text-fg shadow-sm'
                  : 'text-fg-muted hover:text-fg'
              }`}
            >
              👥 Community UGC
            </button>
          </div>

          {/* Modality & Category Pills */}
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="text-xs text-fg-faint mr-1">Type:</span>
            {(['ALL', 'IMAGE', 'VIDEO'] as const).map((m) => (
              <button
                key={m}
                type="button"
                onClick={() => setModalityFilter(m)}
                className={`btn btn-sm ${
                  modalityFilter === m ? 'btn-primary' : 'btn-secondary text-fg-muted'
                }`}
              >
                {m === 'ALL' && 'All Types'}
                {m === 'IMAGE' && '🖼️ Images'}
                {m === 'VIDEO' && '🎬 Videos'}
              </button>
            ))}

            <div className="h-4 w-px bg-border mx-1" />

            {(['ALL', 'GAME', 'PHOTO', 'VIDEO', 'ART'] as const).map((cat) => (
              <button
                key={cat}
                type="button"
                onClick={() => setCategoryFilter(cat)}
                className={`btn btn-sm ${
                  categoryFilter === cat ? 'btn-primary' : 'btn-secondary text-fg-muted'
                }`}
              >
                {cat === 'ALL' && 'All Topics'}
                {cat === 'GAME' && '🎮 Game'}
                {cat === 'PHOTO' && '📸 Photo'}
                {cat === 'VIDEO' && '🎬 Motion'}
                {cat === 'ART' && '🎨 Art'}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Gallery Grid */}
      <div className="mt-6 grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3">
        {filteredItems.map((item) => (
          <div
            key={item.id}
            onClick={() => setActiveItem(item)}
            className="card-hover group flex cursor-pointer flex-col overflow-hidden rounded-xl border border-border bg-surface shadow-sm transition-all hover:border-violet-500 hover:shadow-lg"
          >
            {/* Thumbnail / Video Preview */}
            <div className="relative aspect-video w-full overflow-hidden bg-slate-900">
              {item.modality === 'VIDEO' ? (
                <video
                  src={item.outputUrl}
                  poster={item.thumbnailUrl}
                  muted
                  loop
                  playsInline
                  onMouseEnter={(e) => void e.currentTarget.play()}
                  onMouseLeave={(e) => {
                    e.currentTarget.pause();
                    e.currentTarget.currentTime = 0;
                  }}
                  className="h-full w-full object-cover transition-transform duration-300 group-hover:scale-105"
                />
              ) : (
                <img
                  src={item.thumbnailUrl}
                  alt={item.title}
                  className="h-full w-full object-cover transition-transform duration-300 group-hover:scale-105"
                />
              )}

              {/* Overlay Badges */}
              <div className="absolute left-2.5 top-2.5 flex items-center gap-1.5">
                <span className="pill pill-violet bg-black/70 text-white backdrop-blur text-[10px]">
                  {item.modality === 'VIDEO' ? '🎬 Video' : '🖼️ Image'}
                </span>
                {item.isOfficial && (
                  <span className="pill pill-amber bg-amber-900/80 text-amber-200 backdrop-blur text-[10px]">
                    Official
                  </span>
                )}
              </div>

              <div className="absolute bottom-2.5 right-2.5 flex items-center gap-1.5">
                <span className="pill pill-emerald bg-black/70 text-white backdrop-blur text-[10px]">
                  {item.model}
                </span>
                <span className="pill bg-black/70 text-white backdrop-blur text-[10px]">
                  {item.aspectRatio}
                </span>
              </div>
            </div>

            {/* Card Content */}
            <div className="flex flex-1 flex-col justify-between p-4">
              <div>
                {/* Lineage Token */}
                {item.forkedFromTitle && (
                  <div className="mb-1.5 inline-flex items-center gap-1 rounded bg-violet-50 px-2 py-0.5 text-[10px] font-medium text-violet-700 dark:bg-violet-950/40 dark:text-violet-300">
                    <span>🌱 Remix of: {item.forkedFromTitle}</span>
                  </div>
                )}

                <h3 className="text-sm font-bold text-fg group-hover:text-primary">
                  {item.title}
                </h3>
                <p className="mt-1 line-clamp-2 text-xs text-fg-muted">
                  {item.prompt}
                </p>
              </div>

              {/* Card Footer */}
              <div className="mt-4 flex items-center justify-between border-t border-border pt-3 text-xs text-fg-faint">
                <div className="flex items-center gap-1.5">
                  <span className="font-medium text-fg">{item.author}</span>
                  <span>· {item.createdAt}</span>
                </div>

                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    onClick={(e) => handleLike(item.id, e)}
                    className="flex items-center gap-1 rounded px-2 py-1 text-fg-subtle hover:bg-bg-muted hover:text-rose-500"
                  >
                    <span>❤️</span>
                    <span className="text-[11px] font-semibold">{item.likes}</span>
                  </button>

                  <button
                    type="button"
                    onClick={(e) => handleRemix(item, e)}
                    className="btn btn-secondary btn-sm flex items-center gap-1 font-semibold text-primary hover:btn-primary"
                  >
                    <span>⚡ Remix</span>
                  </button>
                </div>
              </div>
            </div>
          </div>
        ))}
      </div>

      {filteredItems.length === 0 && (
        <div className="mt-12 rounded-lg border border-dashed border-border p-12 text-center">
          <p className="text-sm font-semibold text-fg">No creations found</p>
          <p className="mt-1 text-xs text-fg-faint">
            Try adjusting your search keywords or switching filters above.
          </p>
        </div>
      )}

      {/* Lightbox / Detail Modal */}
      {activeItem && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-4 backdrop-blur-md">
          <div className="card flex max-h-[92vh] w-full max-w-5xl flex-col overflow-hidden border-border bg-surface p-0 shadow-2xl lg:flex-row">
            {/* Left Preview Side */}
            <div className="relative flex flex-1 items-center justify-center bg-black/90 p-4">
              {activeItem.modality === 'VIDEO' ? (
                <video
                  src={activeItem.outputUrl}
                  controls
                  autoPlay
                  loop
                  className="max-h-[70vh] w-full object-contain"
                />
              ) : (
                <img
                  src={activeItem.outputUrl}
                  alt={activeItem.title}
                  className="max-h-[70vh] w-full object-contain"
                />
              )}
            </div>

            {/* Right Information Side */}
            <div className="flex w-full flex-col justify-between p-6 lg:w-[420px]">
              <div>
                <div className="flex items-center justify-between">
                  <span className="pill pill-violet text-xs">
                    {activeItem.isOfficial ? 'Official Recipe' : 'Community Creation'}
                  </span>
                  <button
                    type="button"
                    onClick={() => setActiveItem(null)}
                    className="rounded p-1 text-fg-faint hover:bg-bg-muted hover:text-fg"
                  >
                    ✕
                  </button>
                </div>

                <h2 className="mt-3 text-lg font-bold text-fg">
                  {activeItem.title}
                </h2>

                <div className="mt-1 flex items-center gap-2 text-xs text-fg-faint">
                  <span>Created by <strong className="text-fg">{activeItem.author}</strong></span>
                  <span>· {activeItem.createdAt}</span>
                </div>

                {/* Lineage Token */}
                {activeItem.forkedFromTitle && (
                  <div className="mt-3 rounded border border-violet-200 bg-violet-50/70 p-2 text-xs text-violet-800 dark:border-violet-900 dark:bg-violet-950/40 dark:text-violet-300">
                    🌱 <strong>Lineage:</strong> Forked from {activeItem.forkedFromTitle}
                  </div>
                )}

                {/* Prompt Box */}
                <div className="mt-4">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-bold uppercase tracking-wider text-fg-subtle">
                      Prompt
                    </span>
                    <button
                      type="button"
                      onClick={() => handleCopyPrompt(activeItem.prompt)}
                      className="text-xs font-medium text-primary hover:underline"
                    >
                      {copyFeedback ? '✓ Copied!' : 'Copy Prompt'}
                    </button>
                  </div>
                  <div className="mt-1.5 rounded-md border border-border bg-bg-muted p-3 text-xs leading-relaxed text-fg">
                    {activeItem.prompt}
                  </div>
                </div>

                {/* Negative Prompt (if available) */}
                {activeItem.negativePrompt && (
                  <div className="mt-3">
                    <span className="text-[11px] font-bold uppercase tracking-wider text-fg-subtle">
                      Negative Prompt
                    </span>
                    <div className="mt-1 rounded border border-border bg-bg-muted p-2 text-xs text-fg-muted">
                      {activeItem.negativePrompt}
                    </div>
                  </div>
                )}

                {/* Technical Parameters */}
                <div className="mt-4 grid grid-cols-2 gap-2 text-xs">
                  <div className="rounded border border-border bg-surface-alt p-2">
                    <span className="text-fg-faint text-[10px] block">Model</span>
                    <span className="font-semibold text-fg">{activeItem.model}</span>
                  </div>
                  <div className="rounded border border-border bg-surface-alt p-2">
                    <span className="text-fg-faint text-[10px] block">Aspect Ratio</span>
                    <span className="font-semibold text-fg">{activeItem.aspectRatio}</span>
                  </div>
                  {activeItem.duration && (
                    <div className="rounded border border-border bg-surface-alt p-2">
                      <span className="text-fg-faint text-[10px] block">Duration</span>
                      <span className="font-semibold text-fg">{activeItem.duration}</span>
                    </div>
                  )}
                  {activeItem.seed && (
                    <div className="rounded border border-border bg-surface-alt p-2">
                      <span className="text-fg-faint text-[10px] block">Seed</span>
                      <span className="font-mono text-fg">{activeItem.seed}</span>
                    </div>
                  )}
                </div>
              </div>

              {/* Modal Actions */}
              <div className="mt-6 flex items-center gap-3 border-t border-border pt-4">
                <button
                  type="button"
                  onClick={(e) => handleLike(activeItem.id, e)}
                  className="btn btn-secondary flex items-center gap-1.5"
                >
                  <span>❤️</span>
                  <span>{activeItem.likes} Likes</span>
                </button>

                <button
                  type="button"
                  onClick={() => handleRemix(activeItem)}
                  className="btn btn-primary flex-1 font-bold shadow-md"
                >
                  ⚡ Remix in Studio
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
