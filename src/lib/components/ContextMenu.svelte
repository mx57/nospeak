<script lang="ts">
    import { onMount } from 'svelte';
    import { t } from '$lib/i18n';

    let { x = 0, y = 0, isOpen = false, onClose, onCite, onReact, onCopy, onFavorite, isFavorited = false, message } = $props<{
        x: number;
        y: number;
        isOpen: boolean;
        onClose: () => void;
        onCite?: () => void;
        onReact?: (emoji: string) => void;
        onCopy?: () => void;
        onFavorite?: () => void;
        isFavorited?: boolean;
        message?: { sentAt: number } | null;
    }>();

    // Quick-access reactions (Telegram-style)
    const quickEmojis = ['👍', '❤️', '😂', '😢', '😡', '🙏'];

    let showFullPicker = $state(false);
    let pickerLoaded = $state(false);
    let pickerEl: HTMLElement | null = null;

    // Dynamic import of emoji-picker-element — client-side only, SSR-safe
    onMount(() => {
        import('emoji-picker-element').then(() => {
            pickerLoaded = true;
        }).catch((err) => {
            console.error('Failed to load emoji-picker-element:', err);
        });
    });

    // Emoji picker web-component event listener
    $effect(() => {
        if (!pickerLoaded || !showFullPicker) return;
        const el = pickerEl;
        if (!el) return;

        const handler = (e: Event) => {
            const detail = (e as CustomEvent).detail;
            if (detail?.unicode) {
                onReact?.(detail.unicode);
                onClose();
            }
        };

        el.addEventListener('emoji-click', handler);
        return () => el.removeEventListener('emoji-click', handler);
    });

    function handleQuickClick(emoji: string) {
        onReact?.(emoji);
        onClose();
    }

    // Close on outside press (pointerdown) so one tap closes,
    // while long-press open doesn't immediately self-dismiss on release.
    $effect(() => {
        if (!isOpen) return;

        const handlePointerDown = (e: PointerEvent) => {
            const target = e.target as HTMLElement | null;
            if (!target?.closest('.context-menu')) {
                e.stopPropagation();
                e.preventDefault();
                onClose();
            }
        };

        // Use capture so we close before other handlers run.
        document.addEventListener('pointerdown', handlePointerDown, true);

        return () => {
            document.removeEventListener('pointerdown', handlePointerDown, true);
        };
    });

    function portal(node: HTMLElement) {
        document.body.appendChild(node);
        return {
            destroy() {
                if (node.parentNode) {
                    node.parentNode.removeChild(node);
                }
            }
        };
    }

    function reposition(node: HTMLElement, coords: { x: number, y: number }) {
        const update = ({ x, y }: { x: number, y: number }) => {
            const rect = node.getBoundingClientRect();
            const { innerWidth, innerHeight } = window;
            const padding = 8;

            let safeX = x;
            let safeY = y;

            // Horizontal clamping
            if (safeX + rect.width > innerWidth - padding) {
                safeX = innerWidth - rect.width - padding;
            }
            if (safeX < padding) {
                safeX = padding;
            }

            // Vertical clamping
            if (safeY + rect.height > innerHeight - padding) {
                safeY = innerHeight - rect.height - padding;
            }
            if (safeY < padding) {
                safeY = padding;
            }

            node.style.left = `${safeX}px`;
            node.style.top = `${safeY}px`;
        };

        update(coords);

        return {
            update
        };
    }
</script>

{#if isOpen}
    <div
        use:portal
        use:reposition={{x, y}}
        class="context-menu fixed bg-white/80 dark:bg-slate-900/80 backdrop-blur-xl border border-gray-200 dark:border-slate-700 rounded-lg shadow-xl py-1 z-[9999] min-w-[180px] outline-none"
        role="menu"
    >
        {#if onReact && !showFullPicker}
            <!-- Quick reactions row -->
            <div class="flex items-center px-2 pt-1.5 pb-1.5 gap-1 border-b border-gray-200/70 dark:border-slate-700/70 overflow-x-auto">
                {#each quickEmojis as emoji}
                    <button
                        type="button"
                        class="flex-none w-8 h-8 flex items-center justify-center rounded-lg hover:bg-gray-100/70 dark:hover:bg-slate-700/70 text-lg transition-transform hover:scale-110 active:scale-95"
                        onclick={() => handleQuickClick(emoji)}
                        aria-label={emoji}
                    >
                        {emoji}
                    </button>
                {/each}
                <button
                    type="button"
                    class="flex-none w-8 h-8 flex items-center justify-center rounded-lg hover:bg-gray-100/70 dark:hover:bg-slate-700/70 text-base text-gray-500 dark:text-slate-400 transition-colors"
                    onclick={() => { showFullPicker = true; }}
                    aria-label="More reactions"
                >
                    +
                </button>
            </div>
        {/if}

        {#if showFullPicker}
            <!-- Full emoji picker -->
            <div class="relative">
                <button
                    type="button"
                    class="absolute top-2 left-2 z-10 w-7 h-7 flex items-center justify-center rounded-full bg-white/80 dark:bg-slate-800/80 text-sm text-gray-600 dark:text-slate-300 hover:bg-gray-200/80 dark:hover:bg-slate-600/80 transition-colors shadow-sm border border-gray-200/60 dark:border-slate-600/60"
                    onclick={() => { showFullPicker = false; }}
                    aria-label="Back"
                >
                    ←
                </button>
                {#if pickerLoaded}
                    <emoji-picker
                        bind:this={pickerEl}
                        class="emoji-picker-custom"
                        style="
                            --background: transparent;
                            --border-color: transparent;
                            --text-color: inherit;
                            --input-background: rgba(0,0,0,0.05);
                            --input-border-color: rgba(0,0,0,0.1);
                            --input-placeholder-color: rgba(0,0,0,0.35);
                            --input-text-color: inherit;
                            --indicator-color: #3b82f6;
                            --button-active-background: rgba(59,130,246,0.15);
                            --button-hover-background: rgba(0,0,0,0.05);
                        "
                    ></emoji-picker>
                {:else}
                    <div class="flex items-center justify-center h-40 text-sm text-gray-400 dark:text-slate-500">
                        Loading…
                    </div>
                {/if}
            </div>
        {:else if message?.sentAt}
            <div class="px-4 py-2 text-xs text-gray-600 dark:text-slate-400 {onReact || onCite || onCopy || onFavorite ? 'border-b border-gray-200/70 dark:border-slate-700/70' : ''}">
                {$t('chat.contextMenu.sentAt')}: {new Date(message.sentAt).toLocaleString()}
            </div>
        {/if}

        {#if !showFullPicker}
            {#if onCite}
                <button
                    class="w-full text-start px-4 py-2 hover:bg-gray-100/50 dark:hover:bg-slate-700/50 text-sm dark:text-white transition-colors"
                    onclick={() => { onCite(); onClose(); }}
                >
                    {$t('chat.contextMenu.cite')}
                </button>
            {/if}
            {#if onCopy}
                <button
                    class="w-full text-start px-4 py-2 hover:bg-gray-100/50 dark:hover:bg-slate-700/50 text-sm dark:text-white transition-colors"
                    onclick={() => { onCopy(); onClose(); }}
                >
                    {$t('chat.contextMenu.copy')}
                </button>
            {/if}
            {#if onFavorite}
                <button
                    class="w-full text-start px-4 py-2 hover:bg-gray-100/50 dark:hover:bg-slate-700/50 text-sm dark:text-white transition-colors"
                    onclick={() => { onFavorite(); onClose(); }}
                >
                    {isFavorited ? $t('chat.contextMenu.unfavorite') : $t('chat.contextMenu.favorite')}
                </button>
            {/if}
        {/if}
    </div>
{/if}

<style>
    :global(.emoji-picker-custom) {
        --num-columns: 7;
        --category-emoji-size: 1.2rem;
        --emoji-size: 1.35rem;
        height: 320px;
        width: 260px;
        border: none;
        background: transparent;
    }

    :global(.dark .emoji-picker-custom) {
        --input-background: rgba(255,255,255,0.08);
        --input-border-color: rgba(255,255,255,0.15);
        --input-placeholder-color: rgba(255,255,255,0.35);
        --input-text-color: #e2e8f0;
        --button-hover-background: rgba(255,255,255,0.08);
    }
</style>
