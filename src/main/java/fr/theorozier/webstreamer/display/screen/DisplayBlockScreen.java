package fr.theorozier.webstreamer.display.screen;

import fr.theorozier.webstreamer.WebStreamerClientMod;
import fr.theorozier.webstreamer.display.BigTVBlock;
import fr.theorozier.webstreamer.display.BigTVBlockEntity;
import fr.theorozier.webstreamer.display.DisplayBlock;
import fr.theorozier.webstreamer.display.DisplayBlockEntity;
import fr.theorozier.webstreamer.display.DisplayNetworking;
import fr.theorozier.webstreamer.display.TVBlock;
import fr.theorozier.webstreamer.display.TVBlockEntity;
import fr.theorozier.webstreamer.display.WebDisplayPBlock;
import fr.theorozier.webstreamer.display.source.DisplaySource;
import fr.theorozier.webstreamer.display.source.RawDisplaySource;
import fr.theorozier.webstreamer.display.source.TwitchDisplaySource;
import fr.theorozier.webstreamer.display.source.YoutubeDisplaySource;
import fr.theorozier.webstreamer.playlist.Playlist;
import fr.theorozier.webstreamer.playlist.PlaylistQuality;
import fr.theorozier.webstreamer.twitch.TwitchClient;
import fr.theorozier.webstreamer.youtube.YoutubeClient;
import fr.theorozier.webstreamer.util.AsyncProcessor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.*;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

import java.net.URISyntaxException;
import java.util.function.Consumer;
import java.util.concurrent.*;
import java.util.List;
import java.net.URI;

/**
 * <p>This screen is the GUI that the player can use to configure a display block
 * entity.</p>
 */
@Environment(EnvType.CLIENT)
public class DisplayBlockScreen extends Screen {

    private static final Text CONF_TEXT = Text.translatable("gui.webstreamer.display.conf");
    private static final Text WIDTH_TEXT = Text.translatable("gui.webstreamer.display.width");
    private static final Text HEIGHT_TEXT = Text.translatable("gui.webstreamer.display.height");
    private static final Text OFFSET_X_TEXT = Text.translatable("gui.webstreamer.display.offsetX");
    private static final Text OFFSET_Y_TEXT = Text.translatable("gui.webstreamer.display.offsetY");
    private static final Text OFFSET_Z_TEXT = Text.translatable("gui.webstreamer.display.offsetZ");
    private static final Text SOURCE_TYPE_TEXT = Text.translatable("gui.webstreamer.display.sourceType");
    private static final Text SOURCE_TYPE_RAW_TEXT = Text.translatable("gui.webstreamer.display.sourceType.raw");
    private static final Text SOURCE_TYPE_TWITCH_TEXT = Text.translatable("gui.webstreamer.display.sourceType.twitch");
    private static final Text SOURCE_TYPE_YOUTUBE_TEXT = Text.translatable("gui.webstreamer.display.sourceType.youtube");
    private static final Text URL_TEXT = Text.translatable("gui.webstreamer.display.url");
    private static final Text CHANNEL_TEXT = Text.translatable("gui.webstreamer.display.channel");
    private static final Text VIDEO_ID_TEXT = Text.literal("YouTube URL / Playlist URL");
    private static final Text NO_QUALITY_TEXT = Text.translatable("gui.webstreamer.display.noQuality");
    private static final Text QUALITY_TEXT = Text.translatable("gui.webstreamer.display.quality");
    private static final String AUDIO_DISTANCE_TEXT_KEY = "gui.webstreamer.display.audioDistance";
    private static final String AUDIO_VOLUME_TEXT_KEY = "gui.webstreamer.display.audioVolume";

    private static final Text ERR_PENDING = Text.translatable("gui.webstreamer.display.error.pending");
    private static final Text ERR_INVALID_SIZE = Text.translatable("gui.webstreamer.display.error.invalidSize");
    private static final Text ERR_INVALID_OFFSET = Text.translatable("gui.webstreamer.display.error.invalidOffset");
    private static final Text ERR_TWITCH = Text.translatable("gui.webstreamer.display.error.twitch");
    private static final Text ERR_NO_TOKEN_TEXT = Text.translatable("gui.webstreamer.display.error.noToken");
    private static final Text ERR_CHANNEL_NOT_FOUND_TEXT = Text.translatable("gui.webstreamer.display.error.channelNotFound");
    private static final Text ERR_CHANNEL_OFFLINE_TEXT = Text.translatable("gui.webstreamer.display.error.channelOffline");
    private static final Text ERR_YOUTUBE = Text.translatable("gui.webstreamer.display.error.youtube");
    private static final Text ERR_YOUTUBE_NOT_FOUND_TEXT = Text.translatable("gui.webstreamer.display.error.youtubeNotFound");
    private static final Text ERR_YOUTUBE_UNAVAILABLE_TEXT = Text.translatable("gui.webstreamer.display.error.youtubeUnavailable");
    private static final Text ERR_YOUTUBE_NO_STREAMS_TEXT = Text.translatable("gui.webstreamer.display.error.youtubeNoStreams");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AsyncProcessor<String, Playlist, TwitchClient.PlaylistException> asyncPlaylist = new AsyncProcessor<>(WebStreamerClientMod.TWITCH_CLIENT::requestPlaylist, false);
    private final AsyncProcessor<String, Playlist, YoutubeClient.YoutubeException> asyncYoutubePlaylist = new AsyncProcessor<>(WebStreamerClientMod.YOUTUBE_CLIENT::requestPlaylist, false);

    /** The block entity this screen is opened on. The following fields are temporaries to save later. */
    private final DisplayBlockEntity display;
    private final boolean fixedScaleOffset;

    private TextFieldWidget widthField, heightField, offsetXField, offsetYField, offsetZField;
    private AudioDistanceSliderWidget audioDistanceSlider;
    private AudioVolumeSliderWidget audioVolumeSlider;
    private ButtonWidget sourceTypeButton;
    private SourceType sourceType;
    private TextWidget errorText;

    private TextFieldWidget rawUriField;

    private TextFieldWidget twitchChannelField;
    private QualitySliderWidget twitchQualitySlider;
    private TextWidget twitchQualityText;
    private Playlist twitchPlaylist;
    private TwitchClient.PlaylistException twitchPlaylistExc;

    private TextFieldWidget youtubeVideoIdField;
    private QualitySliderWidget youtubeQualitySlider;
    private TextWidget youtubeQualityText;
    private Playlist youtubePlaylist;
    private YoutubeClient.YoutubeException youtubePlaylistExc;
    private ButtonWidget youtubePrevButton;
    private ButtonWidget youtubeNextButton;
    private TextWidget youtubePlaylistStatusText;

    private boolean dirty;
    private boolean commitOnClose = true;
    private ButtonWidget doneButton;

    public DisplayBlockScreen(DisplayBlockEntity display) {
        super(ScreenTexts.EMPTY);
        this.display = display;
        this.fixedScaleOffset = display instanceof TVBlockEntity || display instanceof BigTVBlockEntity;
    }

    @Override
    protected void init() {
        // Check if this display is locked to creative players only
        if (this.display.requiresOp() && (this.client == null || this.client.player == null || !this.client.player.isCreative())) {
            this.close();
            return;
        }

        int xHalf = this.width / 2;
        int yTop = 60;

        if (this.height < 320) {
            yTop = 35;
        }

        TextWidget confText = new TextWidget(this.width, 0, CONF_TEXT, this.textRenderer);
        confText.setPosition(0, 20);
        confText.setTextColor(0xFFFFFF);
        this.addDrawableChild(confText);

        // Size section
        TextWidget widthText = new TextWidget(WIDTH_TEXT, this.textRenderer);
        widthText.setPosition(xHalf - 154, yTop + 1);
        widthText.setTextColor(0xA0A0A0);
        widthText.alignLeft();
        this.addDrawableChild(widthText);

        TextWidget heightText = new TextWidget(HEIGHT_TEXT, this.textRenderer);
        heightText.setPosition(xHalf - 96, yTop + 1);
        heightText.setTextColor(0xA0A0A0);
        heightText.alignLeft();
        this.addDrawableChild(heightText);

        String widthVal = widthField == null ? Float.toString(this.display.getWidth()) : widthField.getText();
        widthField = new TextFieldWidget(this.textRenderer, xHalf - 154, yTop + 11, 50, 18, Text.empty());
        widthField.setText(widthVal);
        widthField.setEditable(!this.fixedScaleOffset);
        widthField.setChangedListener(val -> this.dirty = true);
        this.addDrawableChild(widthField);

        String heightVal = heightField == null ? Float.toString(this.display.getHeight()) : heightField.getText();
        heightField = new TextFieldWidget(this.textRenderer, xHalf - 96, yTop + 11, 50, 18, Text.empty());
        heightField.setText(heightVal);
        heightField.setEditable(!this.fixedScaleOffset);
        heightField.setChangedListener(val -> this.dirty = true);
        this.addDrawableChild(heightField);

        // Offset section
        TextWidget offsetXText = new TextWidget(OFFSET_X_TEXT, this.textRenderer);
        offsetXText.setPosition(xHalf - 38, yTop + 1);
        offsetXText.setTextColor(0xA0A0A0);
        offsetXText.alignLeft();
        this.addDrawableChild(offsetXText);

        TextWidget offsetYText = new TextWidget(OFFSET_Y_TEXT, this.textRenderer);
        offsetYText.setPosition(xHalf + 20, yTop + 1);
        offsetYText.setTextColor(0xA0A0A0);
        offsetYText.alignLeft();
        this.addDrawableChild(offsetYText);

        TextWidget offsetZText = new TextWidget(OFFSET_Z_TEXT, this.textRenderer);
        offsetZText.setPosition(xHalf + 78, yTop + 1);
        offsetZText.setTextColor(0xA0A0A0);
        offsetZText.alignLeft();
        this.addDrawableChild(offsetZText);

        String offsetXVal = offsetXField == null ? Double.toString(this.display.getOffsetX()) : offsetXField.getText();
        offsetXField = new TextFieldWidget(this.textRenderer, xHalf - 38, yTop + 11, 50, 18, Text.empty());
        offsetXField.setText(offsetXVal);
        offsetXField.setEditable(!this.fixedScaleOffset);
        offsetXField.setChangedListener(val -> this.dirty = true);
        this.addDrawableChild(offsetXField);

        String offsetYVal = offsetYField == null ? Double.toString(this.display.getOffsetY()) : offsetYField.getText();
        offsetYField = new TextFieldWidget(this.textRenderer, xHalf + 20, yTop + 11, 50, 18, Text.empty());
        offsetYField.setText(offsetYVal);
        offsetYField.setEditable(!this.fixedScaleOffset);
        offsetYField.setChangedListener(val -> this.dirty = true);
        this.addDrawableChild(offsetYField);

        String offsetZVal = offsetZField == null ? Double.toString(this.display.getOffsetZ()) : offsetZField.getText();
        offsetZField = new TextFieldWidget(this.textRenderer, xHalf + 78, yTop + 11, 50, 18, Text.empty());
        offsetZField.setText(offsetZVal);
        offsetZField.setEditable(!this.fixedScaleOffset);
        offsetZField.setChangedListener(val -> this.dirty = true);
        this.addDrawableChild(offsetZField);

        DisplaySource source = this.display.getSource();
        if (this.sourceType == null) {
            this.sourceType = SourceType.YOUTUBE;
            if (source instanceof TwitchDisplaySource) {
                this.sourceType = SourceType.TWITCH;
            } else if (source instanceof RawDisplaySource rawSource && rawSource.getUri() != null) {
                this.sourceType = SourceType.RAW;
            }
        }

        sourceTypeButton = ButtonWidget.builder(this.sourceType.getText(), button -> {
            SourceType[] values = SourceType.values();
            this.sourceType = values[(this.sourceType.ordinal() + 1) % values.length];
            button.setMessage(this.sourceType.getText());
            if (this.client != null) {
                this.init(this.client, this.width, this.height);
            }
        }).dimensions(xHalf + 136, yTop + 10, 76, 20).build();
        this.addDrawableChild(sourceTypeButton);

        float audioDistanceVal = audioDistanceSlider == null ? this.display.getAudioDistance() : audioDistanceSlider.getDistance();
        boolean isBaseDisplay = this.display.getCachedState().getBlock() instanceof DisplayBlock && !(this.display.getCachedState().getBlock() instanceof WebDisplayPBlock)&& !(this.display.getCachedState().getBlock() instanceof TVBlock)&& !(this.display.getCachedState().getBlock() instanceof BigTVBlock);
        float maxAudioDistance = isBaseDisplay ? 512f : 64f;
        audioDistanceSlider = new AudioDistanceSliderWidget(xHalf - 154, yTop + 36, 150, 20, audioDistanceVal, maxAudioDistance);
        audioDistanceSlider.setChangedListener(val -> this.dirty = true);
        this.addDrawableChild(audioDistanceSlider);

        float audioVolumeVal = audioVolumeSlider == null ? this.display.getAudioVolume() : audioVolumeSlider.getVolume();
        audioVolumeSlider = new AudioVolumeSliderWidget(xHalf + 4, yTop + 36, 150, 20, audioVolumeVal);
        audioVolumeSlider.setChangedListener(val -> this.dirty = true);
        this.addDrawableChild(audioVolumeSlider);

        int ySourceTop = yTop + 70;
        int ySourceBottom = ySourceTop;

        if (sourceType == SourceType.RAW) {

            TextWidget uriText = new TextWidget(URL_TEXT, this.textRenderer);
            uriText.setPosition(xHalf - 154, ySourceTop);
            uriText.setTextColor(0xA0A0A0);
            uriText.alignLeft();
            this.addDrawableChild(uriText);

            String rawUriVal = "";
            if (rawUriField != null) {
                rawUriVal = rawUriField.getText();
            } else if (source instanceof RawDisplaySource rawSource) {
                if (rawSource.getUri() != null) {
                    rawUriVal = rawSource.getUri().toString();
                }
            }
            rawUriField = new TextFieldWidget(this.textRenderer, xHalf - 154, ySourceTop + 10, 308, 20, Text.empty());
            rawUriField.setMaxLength(32000);
            rawUriField.setText(rawUriVal);
            rawUriField.setChangedListener(val -> this.dirty = true);
            this.addDrawableChild(rawUriField);

            ySourceBottom += 10 + 40;

        } else if (sourceType == SourceType.TWITCH) {

            TextWidget channelText = new TextWidget(CHANNEL_TEXT, this.textRenderer);
            channelText.setPosition(xHalf - 154, ySourceTop);
            channelText.setTextColor(0xA0A0A0);
            channelText.alignLeft();
            this.addDrawableChild(channelText);

            String twitchChannelVal = "";
            if (twitchChannelField != null) {
                twitchChannelVal = twitchChannelField.getText();
            } else if (source instanceof TwitchDisplaySource twitchSource) {
                twitchChannelVal = twitchSource.getChannel();
                this.asyncPlaylist.push(twitchChannelVal);
            } else {
                this.asyncPlaylist.push("");
            }

            twitchChannelField = new TextFieldWidget(this.textRenderer, xHalf - 154, ySourceTop + 10, 308, 20, Text.empty());
            twitchChannelField.setMaxLength(64);
            twitchChannelField.setText(twitchChannelVal);
            twitchChannelField.setChangedListener(val -> {
                this.asyncPlaylist.push(val);
                this.dirty = true;
            });
            this.addDrawableChild(this.twitchChannelField);

            twitchQualityText = new TextWidget(QUALITY_TEXT, this.textRenderer);
            twitchQualityText.setPosition(xHalf - 154, ySourceTop + 40);
            twitchQualityText.setTextColor(0xA0A0A0);
            twitchQualityText.alignLeft();
            this.addDrawableChild(twitchQualityText);

            twitchQualitySlider = new QualitySliderWidget(xHalf - 154, ySourceTop + 50, 308, 20, twitchQualitySlider);
            twitchQualitySlider.setChangedListener(val -> this.dirty = true);
            this.addDrawableChild(twitchQualitySlider);

            ySourceBottom += 50 + 40;

        } else if (sourceType == SourceType.YOUTUBE) {

            TextWidget videoIdText = new TextWidget(VIDEO_ID_TEXT, this.textRenderer);
            videoIdText.setPosition(xHalf - 154, ySourceTop);
            videoIdText.setTextColor(0xA0A0A0);
            videoIdText.alignLeft();
            this.addDrawableChild(videoIdText);

            String youtubeVideoIdVal = "";
            if (youtubeVideoIdField != null) {
                youtubeVideoIdVal = youtubeVideoIdField.getText();
            } else if (source instanceof YoutubeDisplaySource youtubeSource) {
                if (youtubeSource.hasPlaylist()) {
                    youtubeVideoIdVal = youtubeSource.getSourceText();
                } else {
                    youtubeVideoIdVal = youtubeSource.getVideoId();
                }
                List<String> ids = parseYoutubeVideoIds(youtubeVideoIdVal);
                this.asyncYoutubePlaylist.push(ids.isEmpty() ? "" : ids.get(0));
            } else {
                this.asyncYoutubePlaylist.push("");
            }

            youtubeVideoIdField = new TextFieldWidget(this.textRenderer, xHalf - 154, ySourceTop + 10, 308, 20, Text.empty());
            youtubeVideoIdField.setMaxLength(1024);
            youtubeVideoIdField.setText(youtubeVideoIdVal);
            youtubeVideoIdField.setChangedListener(val -> {
                List<String> ids = parseYoutubeVideoIds(val);
                this.asyncYoutubePlaylist.push(ids.isEmpty() ? "" : ids.get(0));
                this.dirty = true;
            });

            this.addDrawableChild(this.youtubeVideoIdField);

            youtubeQualityText = new TextWidget(QUALITY_TEXT, this.textRenderer);
            youtubeQualityText.setPosition(xHalf - 154, ySourceTop + 40);
            youtubeQualityText.setTextColor(0xA0A0A0);
            youtubeQualityText.alignLeft();
            this.addDrawableChild(youtubeQualityText);

            youtubeQualitySlider = new QualitySliderWidget(xHalf - 154, ySourceTop + 50, 308, 20, youtubeQualitySlider);
            youtubeQualitySlider.setChangedListener(val -> this.dirty = true);
            this.addDrawableChild(youtubeQualitySlider);

            youtubePrevButton = ButtonWidget.builder(Text.literal("Prev"), button -> this.onYoutubePlaylistPrevious())
                    .dimensions(xHalf + 4, ySourceTop + 95, 75, 20)
                    .build();
            youtubeNextButton = ButtonWidget.builder(Text.literal("Next"), button -> this.onYoutubePlaylistNext())
                    .dimensions(xHalf + 83, ySourceTop + 95, 75, 20)
                    .build();
            this.addDrawableChild(youtubePrevButton);
            this.addDrawableChild(youtubeNextButton);

            youtubePlaylistStatusText = new TextWidget(this.width, 0, Text.empty(), this.textRenderer);
            youtubePlaylistStatusText.setPosition(xHalf + 4, ySourceTop + 120);
            youtubePlaylistStatusText.setTextColor(0xA0A0A0);
            youtubePlaylistStatusText.alignLeft();
            youtubePlaylistStatusText.visible = false;
            this.addDrawableChild(youtubePlaylistStatusText);

            updateYoutubePlaylistControls(source instanceof YoutubeDisplaySource youtubeSource ? youtubeSource : null);
            ySourceBottom += 95 + 40;

        }

        errorText = new TextWidget(this.width, 0, Text.empty(), this.textRenderer);
        errorText.setPosition(0, ySourceBottom);
        errorText.setTextColor(0xFF6052);
        errorText.visible = false;
        this.addDrawableChild(errorText);

        int yButtonTop = Math.min(Math.max(height / 4 + 120 + 12, ySourceBottom + 20), this.height - 25);

        // Lock button to toggle creative-only access (only for creative mode players)
        boolean isCreativePlayer = this.client != null && this.client.player != null && this.client.player.isCreative();
        if (isCreativePlayer) {
            ButtonWidget lockButton = ButtonWidget.builder(Text.literal(this.display.requiresOp() ? "Creative Only: On" : "Creative Only: Off"), button -> {
                this.display.setRequiresOp(!this.display.requiresOp());
                button.setMessage(Text.literal(this.display.requiresOp() ? "Creative Only: On" : "Creative Only: Off"));
                this.dirty = true;
            }).dimensions(xHalf - 4 - 150, yButtonTop - 30, 150, 20).build();
            this.addDrawableChild(lockButton);
        }

        doneButton = ButtonWidget.builder(ScreenTexts.DONE, button -> this.commitAndClose())
                .dimensions(xHalf - 4 - 150, yButtonTop, 150, 20)
                .build();
        doneButton.active = false;
        this.addDrawableChild(doneButton);

        ButtonWidget cancelButton = ButtonWidget.builder(ScreenTexts.CANCEL, button -> this.cancelAndClose())
                .dimensions(xHalf + 4, yButtonTop, 150, 20)
                .build();
        this.addDrawableChild(cancelButton);

        this.dirty = true;

    }

    /**
     * Show that the current configuration is valid and the "done" button can be pressed.
     */
    private void showValid() {
        this.doneButton.active = true;
        this.errorText.visible = false;
    }

    /**
     * Show that the current configuration contains an error and the "done" button cannot be pressed.
     * @param message The message for the error.
     */
    private void showError(Text message) {
        this.doneButton.active = false;
        this.errorText.setMessage(message);
        this.errorText.visible = true;
    }

    private void updateYoutubePlaylistControls(YoutubeDisplaySource youtubeSource) {
        boolean playlist = youtubeSource != null && youtubeSource.hasPlaylist();
        if (this.youtubePlaylistStatusText != null) {
            if (playlist) {
                this.youtubePlaylistStatusText.setMessage(Text.literal(
                        String.format("Playlist %d/%d", youtubeSource.getPlaylistIndex() + 1, youtubeSource.getPlaylistSize())));
            } else {
                this.youtubePlaylistStatusText.setMessage(Text.empty());
            }
            this.youtubePlaylistStatusText.visible = playlist;
        }
        if (this.youtubePrevButton != null) {
            this.youtubePrevButton.visible = playlist;
            this.youtubePrevButton.active = playlist;
        }
        if (this.youtubeNextButton != null) {
            this.youtubeNextButton.visible = playlist;
            this.youtubeNextButton.active = playlist;
        }
    }

    private void onYoutubePlaylistPrevious() {
        DisplaySource source = this.display.getSource();
        if (source instanceof YoutubeDisplaySource youtubeSource && youtubeSource.previousVideo()) {
            this.display.setSource(youtubeSource);
            DisplayNetworking.sendDisplayUpdate(this.display);
            updateYoutubePlaylistControls(youtubeSource);
            this.dirty = true;
        }
    }

    private void onYoutubePlaylistNext() {
        DisplaySource source = this.display.getSource();
        if (source instanceof YoutubeDisplaySource youtubeSource && youtubeSource.advanceVideo()) {
            this.display.setSource(youtubeSource);
            DisplayNetworking.sendDisplayUpdate(this.display);
            updateYoutubePlaylistControls(youtubeSource);
            this.dirty = true;
        }
    }

    /**
     * Internal function to refresh the state of the "done" button, to activate it only if inputs are valid.
     * @param commit Set to true in order to commit these changes to the display block entity and send an update.
     * @return True if the configuration is valid and, if requested, has been committed.
     */
    private boolean refresh(boolean commit) {

        float width, height;
        double offsetX, offsetY, offsetZ;

        if (this.fixedScaleOffset) {
            width = this.display.getWidth();
            height = this.display.getHeight();
            offsetX = this.display.getOffsetX();
            offsetY = this.display.getOffsetY();
            offsetZ = this.display.getOffsetZ();
        } else {
            try {
                width = Float.parseFloat(this.widthField.getText());
                height = Float.parseFloat(this.heightField.getText());
            } catch (NumberFormatException e) {
                this.showError(ERR_INVALID_SIZE);
                return false;
            }

            try {
                offsetX = Double.parseDouble(this.offsetXField.getText());
                offsetY = Double.parseDouble(this.offsetYField.getText());
                offsetZ = Double.parseDouble(this.offsetZField.getText());
            } catch (NumberFormatException e) {
                this.showError(ERR_INVALID_OFFSET);
                return false;
            }
        }

        URI rawUri = null;
        String twitchChannel = null;
        String twitchQuality = null;
        String youtubeVideoId = null;
        String youtubeQuality = null;

        SourceType sourceType = this.sourceType;

        if (sourceType == SourceType.RAW) {

            String rawUriVal = this.rawUriField.getText();
            if (!rawUriVal.isEmpty()) {
                try {
                    rawUri = new URI(rawUriVal);
                } catch (URISyntaxException e) {
                    this.showError(Text.literal(e.getMessage()));
                    return false;
                }
            }

        } else if (sourceType == SourceType.TWITCH) {

            if (this.asyncPlaylist.requested() || !this.asyncPlaylist.idle()) {
                this.showError(ERR_PENDING);
                return false;
            } else if (this.twitchPlaylistExc != null) {
                this.showError(switch (this.twitchPlaylistExc.getExceptionType()) {
                    case UNKNOWN -> ERR_TWITCH;
                    case NO_TOKEN -> ERR_NO_TOKEN_TEXT;
                    case CHANNEL_NOT_FOUND -> ERR_CHANNEL_NOT_FOUND_TEXT;
                    case CHANNEL_OFFLINE -> ERR_CHANNEL_OFFLINE_TEXT;
                });
                return false;
            } else if (this.twitchPlaylist == null) {
                this.showError(Text.empty());
                return false;
            }

            this.twitchQualitySlider.visible = true;
            this.twitchQualityText.visible = true;

            PlaylistQuality twitchQualityRaw = this.twitchQualitySlider.getQuality();
            if (twitchQualityRaw == null) {
                throw new IllegalStateException("twitch quality should be present if playlist is present");
            }

            twitchChannel = this.twitchPlaylist.getChannel();
            twitchQuality = twitchQualityRaw.name();

        } else if (sourceType == SourceType.YOUTUBE) {

            if (this.asyncYoutubePlaylist.requested() || !this.asyncYoutubePlaylist.idle()) {
                this.showError(ERR_PENDING);
                return false;
            } else if (this.youtubePlaylistExc != null) {
                this.showError(switch (this.youtubePlaylistExc.getExceptionType()) {
                    case FETCH_FAILED, PARSE_FAILED -> ERR_YOUTUBE;
                    case INVALID_VIDEO_ID, VIDEO_NOT_FOUND -> ERR_YOUTUBE_NOT_FOUND_TEXT;
                    case VIDEO_UNAVAILABLE -> ERR_YOUTUBE_UNAVAILABLE_TEXT;
                    case NO_STREAMS -> ERR_YOUTUBE_NO_STREAMS_TEXT;
                });
                return false;
            } else if (this.youtubePlaylist == null) {
                this.showError(Text.empty());
                return false;
            }

            this.youtubeQualitySlider.visible = true;
            this.youtubeQualityText.visible = true;

            PlaylistQuality youtubeQualityRaw = this.youtubeQualitySlider.getQuality();
            if (youtubeQualityRaw == null) {
                throw new IllegalStateException("youtube quality should be present if playlist is present");
            }

            youtubeQuality = youtubeQualityRaw.name();
            youtubeVideoId = this.youtubeVideoIdField != null ? this.youtubeVideoIdField.getText() : this.youtubePlaylist.getChannel();

        }

        this.showValid();

        if (commit) {

            if (!this.fixedScaleOffset) {
                if (this.display.getCachedState().getBlock() instanceof WebDisplayPBlock) {
                    width = Math.min(width, WebDisplayPBlock.MAX_SIZE);
                    height = Math.min(height, WebDisplayPBlock.MAX_SIZE);
                    offsetX = Math.max(-WebDisplayPBlock.MAX_OFFSET, Math.min(WebDisplayPBlock.MAX_OFFSET, offsetX));
                    offsetY = Math.max(-WebDisplayPBlock.MAX_OFFSET, Math.min(WebDisplayPBlock.MAX_OFFSET, offsetY));
                    offsetZ = Math.max(-WebDisplayPBlock.MAX_OFFSET, Math.min(WebDisplayPBlock.MAX_OFFSET, offsetZ));
                }
                this.display.setSize(width, height);
                this.display.setOffset(offsetX, offsetY, offsetZ);
            }

            float audioDistance = this.audioDistanceSlider.getDistance();
            float audioVolume = this.audioVolumeSlider.getVolume();
            boolean isBaseDisplay = this.display.getCachedState().getBlock() instanceof DisplayBlock && !(this.display.getCachedState().getBlock() instanceof WebDisplayPBlock);
            audioDistance = Math.min(audioDistance, isBaseDisplay ? 512f : 64f);
            this.display.setAudioConfig(audioDistance, audioVolume);

            if (sourceType == SourceType.RAW) {
                this.display.setSource(new RawDisplaySource(rawUri));
            } else if (sourceType == SourceType.TWITCH) {
                this.display.setSource(new TwitchDisplaySource(twitchChannel, twitchQuality));
            } else if (sourceType == SourceType.YOUTUBE) {
                String youtubeInput = youtubeVideoId == null ? "" : youtubeVideoId;
                List<String> youtubeIds = parseYoutubeVideoIds(youtubeInput);
                if (youtubeInput.isBlank()) {
                    this.showError(ERR_YOUTUBE);
                    return false;
                }

                if (YoutubeClient.extractPlaylistId(youtubeInput) != null) {
                    try {
                        List<String> playlistIds = WebStreamerClientMod.YOUTUBE_CLIENT.requestPlaylistVideos(youtubeInput);
                        if (!playlistIds.isEmpty()) {
                            youtubeIds = playlistIds;
                        }
                    } catch (YoutubeClient.YoutubeException e) {
                        this.showError(switch (e.getExceptionType()) {
                            case FETCH_FAILED, PARSE_FAILED -> ERR_YOUTUBE;
                            case INVALID_VIDEO_ID, VIDEO_NOT_FOUND -> ERR_YOUTUBE_NOT_FOUND_TEXT;
                            case VIDEO_UNAVAILABLE -> ERR_YOUTUBE_UNAVAILABLE_TEXT;
                            case NO_STREAMS -> ERR_YOUTUBE_NO_STREAMS_TEXT;
                        });
                        return false;
                    }
                }

                if (youtubeIds.isEmpty()) {
                    this.showError(ERR_YOUTUBE);
                    return false;
                }

                if (youtubeIds.size() > 1) {
                    YoutubeDisplaySource newSource = new YoutubeDisplaySource(youtubeIds, youtubeQuality, youtubeInput);
                    if (this.display.getSource() instanceof YoutubeDisplaySource existingYoutubeSource && existingYoutubeSource.hasPlaylist()) {
                        List<String> existingIds = existingYoutubeSource.getVideoIds();
                        if (existingIds.equals(youtubeIds) || youtubeInput.equals(existingYoutubeSource.getSourceText())) {
                            int currentIndex = existingIds.indexOf(existingYoutubeSource.getCurrentVideoId());
                            if (currentIndex >= 0) {
                                newSource.setPlaylistIndex(currentIndex);
                            }
                        }
                    }
                    this.display.setSource(newSource);
                } else {
                    this.display.setSource(new YoutubeDisplaySource(youtubeVideoId.isBlank() ? null : youtubeVideoId, youtubeQuality));
                }
            }

            DisplayNetworking.sendDisplayUpdate(this.display);

        }

        return true;

    }

    /**
     * Internal function to commit the current values to the display block and then close the window. Nothing is
     * committed if any value is invalid.
     */
    @Override
    public void removed() {
        if (this.commitOnClose) {
            this.refresh(true);
        }
        super.removed();
    }

    private void commitAndClose() {
        // Verify player is in creative mode before committing changes if locked
        if (this.display.requiresOp() && (this.client == null || this.client.player == null || !this.client.player.isCreative())) {
            this.commitOnClose = false;
            this.close();
            return;
        }
        if (this.refresh(true)) {
            this.commitOnClose = false;
            this.close();
        }
    }

    /**
     * Internal function to cancel configuration and close the screen.
     */
    private void cancelAndClose() {
        this.commitOnClose = false;
        this.close();
    }

    private static List<String> parseYoutubeVideoIds(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        String[] tokens = input.split("[,;\\s]+");
        List<String> ids = new java.util.ArrayList<>();
        for (String token : tokens) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String playlistId = YoutubeClient.extractPlaylistId(trimmed);
            if (playlistId != null) {
                ids.add(trimmed);
                continue;
            }
            String id = YoutubeClient.extractVideoId(trimmed);
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    @Override
    public void tick() {

        super.tick();

        SourceType sourceType = this.sourceType;

        if (sourceType == SourceType.TWITCH) {
            this.asyncPlaylist.fetch(this.executor, pl -> {
                boolean wasSet = this.twitchQualitySlider.getQuality() != null;
                this.twitchPlaylist = pl;
                this.twitchPlaylistExc = null;
                this.twitchQualitySlider.setQualities(pl.getQualities());
                // If the slider was new and the current source is a twitch one, set its quality.
                if (!wasSet && this.display.getSource() instanceof TwitchDisplaySource twitchSource) {
                    this.twitchQualitySlider.setQuality(twitchSource.getQuality());
                }
                this.dirty = true;
            }, exc -> {
                this.twitchPlaylist = null;
                this.twitchPlaylistExc = exc;
                this.twitchQualitySlider.setQualities(null);
                this.dirty = true;
            });
        } else if (sourceType == SourceType.YOUTUBE) {
            this.asyncYoutubePlaylist.fetch(this.executor, pl -> {
                boolean wasSet = this.youtubeQualitySlider.getQuality() != null;
                this.youtubePlaylist = pl;
                this.youtubePlaylistExc = null;
                this.youtubeQualitySlider.setQualities(pl.getQualities());
                // If the slider was new and the current source is a youtube one, set its quality.
                if (!wasSet && this.display.getSource() instanceof YoutubeDisplaySource youtubeSource) {
                    this.youtubeQualitySlider.setQuality(youtubeSource.getQuality());
                }
                this.dirty = true;
            }, exc -> {
                this.youtubePlaylist = null;
                this.youtubePlaylistExc = exc;
                this.youtubeQualitySlider.setQualities(null);
                this.dirty = true;
            });
        } else {
            this.asyncPlaylist.fetch(this.executor, pl -> {}, exc -> {});
            this.asyncYoutubePlaylist.fetch(this.executor, pl -> {}, exc -> {});
        }

        if (this.dirty) {
            this.refresh(false);
            this.dirty = false;
        }

    }

    /**
     * Internal enumeration for the cycling button between the different menu mode depending on source type.
     */
    private enum SourceType {

        YOUTUBE(SOURCE_TYPE_YOUTUBE_TEXT),
        TWITCH(SOURCE_TYPE_TWITCH_TEXT),
        RAW(SOURCE_TYPE_RAW_TEXT);

        private final Text text;
        SourceType(Text text) {
            this.text = text;
        }

        public Text getText() {
            return text;
        }

    }

    /**
     * Internal class for specializing the slider widget for playlist quality.
     */
    private static class QualitySliderWidget extends SliderWidget {

        private int qualityIndex = -1;
        private List<PlaylistQuality> qualities;
        private Consumer<PlaylistQuality> changedListener;

        public QualitySliderWidget(int x, int y, int width, int height, QualitySliderWidget previousSlider) {
            super(x, y, width, height, Text.empty(), 0.0);
            if (previousSlider != null && previousSlider.qualities != null) {
                this.setQualities(previousSlider.qualities);
                this.qualityIndex = previousSlider.qualityIndex;
                this.value = (double) this.qualityIndex  / (double) (this.qualities.size() - 1);
                this.updateMessage();
            } else {
                this.setQualities(null);
            }
        }

        public void setQualities(List<PlaylistQuality> qualities) {
            this.qualities = qualities;
            this.applyValue();
            this.updateMessage();
        }

        public void setQuality(String quality) {
            for (int i = 0; i < this.qualities.size(); i++) {
                if (this.qualities.get(i).name().equals(quality)) {
                    this.qualityIndex = i;
                    this.value = (double) this.qualityIndex  / (double) (this.qualities.size() - 1);
                    this.updateMessage();
                    if (this.changedListener != null) {
                        this.changedListener.accept(this.qualities.get(i));
                    }
                    return;
                }
            }
        }

        public PlaylistQuality getQuality() {
            if (this.qualities == null) {
                return null;
            }
            try {
                return this.qualities.get(this.qualityIndex);
            } catch (IndexOutOfBoundsException e) {
                return null;
            }
        }

        public void setChangedListener(Consumer<PlaylistQuality> changedListener) {
            this.changedListener = changedListener;
        }

        @Override
        protected void updateMessage() {
            if (this.qualityIndex < 0) {
                this.setMessage(NO_QUALITY_TEXT);
            } else {
                this.setMessage(Text.literal(this.qualities.get(this.qualityIndex).name()));
            }
        }

        @Override
        protected void applyValue() {
            if (this.qualities == null || this.qualities.isEmpty()) {
                this.value = 0.0;
                this.qualityIndex = -1;
                this.active = false;
                if (this.changedListener != null) {
                    this.changedListener.accept(null);
                }
            } else {
                this.qualityIndex = (int) Math.round(this.value * (this.qualities.size() - 1));
                this.value = (double) this.qualityIndex  / (double) (this.qualities.size() - 1);
                this.active = true;
                if (this.changedListener != null) {
                    this.changedListener.accept(this.qualities.get(this.qualityIndex));
                }
            }
        }

    }

    /**
     * Custom slider widget for audio distance to block entity.
     */
    private static class AudioDistanceSliderWidget extends SliderWidget {

        private final float maxDistance;
        private Consumer<Float> changedListener;

        public AudioDistanceSliderWidget(int x, int y, int width, int height, float distance, float maxDistance) {
            super(x, y, width, height, Text.empty(), distance / maxDistance);
            this.maxDistance = maxDistance;
            this.updateMessage();
        }

        public void setChangedListener(Consumer<Float> changedListener) {
            this.changedListener = changedListener;
        }

        public float getDistance() {
            return (float) (this.value * this.maxDistance);
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Text.translatable(AUDIO_DISTANCE_TEXT_KEY).append(": ").append(Integer.toString((int) this.getDistance())));
        }

        @Override
        protected void applyValue() {
            this.changedListener.accept(this.getDistance());
        }

    }

    /**
     * Custom slider widget for audio volume from block entity.
     */
    private static class AudioVolumeSliderWidget extends SliderWidget {

        private Consumer<Float> changedListener;

        public AudioVolumeSliderWidget(int x, int y, int width, int height, float value) {
            super(x, y, width, height, Text.empty(), value);
            this.updateMessage();
        }

        public void setChangedListener(Consumer<Float> changedListener) {
            this.changedListener = changedListener;
        }

        public float getVolume() {
            return (float) this.value;
        }

        @Override
        protected void updateMessage() {
            Text text = (this.value == 0.0) ? ScreenTexts.OFF : Text.literal((int)(this.value * 100.0) + "%");
            this.setMessage(Text.translatable(AUDIO_VOLUME_TEXT_KEY).append(": ").append(text));
        }

        @Override
        protected void applyValue() {
            this.changedListener.accept((float) this.value);
        }

    }

}