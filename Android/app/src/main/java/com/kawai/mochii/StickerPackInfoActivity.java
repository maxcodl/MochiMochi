package com.kawai.mochii;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.List;

public class StickerPackInfoActivity extends BaseActivity {

    private static final String TAG = "StickerPackInfoActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sticker_pack_info);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Pack Info");
        }

        // ── Read all extras ──
        final String trayIconUriString = getIntent().getStringExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_TRAY_ICON);
        final String packName          = getIntent().getStringExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_NAME);
        final String packId            = getIntent().getStringExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_ID);
        final String publisher         = getIntent().getStringExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_PUBLISHER);
        final String website           = getIntent().getStringExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_WEBSITE);
        final String email             = getIntent().getStringExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_EMAIL);
        final String privacyPolicy     = getIntent().getStringExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_PRIVACY_POLICY);
        final String licenseAgreement  = getIntent().getStringExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_LICENSE_AGREEMENT);
        final int stickerCount         = getIntent().getIntExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_STICKER_COUNT, 0);
        final long totalSize           = getIntent().getLongExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_TOTAL_SIZE, 0);
        final StickerPack stickerPack  = getIntent().getParcelableExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_DATA);

        // ── Tray image ──
        ImageView trayImageView = findViewById(R.id.info_tray_image);
        if (!TextUtils.isEmpty(trayIconUriString)) {
            try {
                Uri trayUri = Uri.parse(trayIconUriString);
                InputStream inputStream = getContentResolver().openInputStream(trayUri);
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(inputStream);
                trayImageView.setImageBitmap(bmp);
            } catch (FileNotFoundException e) {
                Log.e(TAG, "Could not find tray icon: " + trayIconUriString);
            }
        }

        // ── Pack name ──
        ((TextView) findViewById(R.id.info_pack_name)).setText(
                TextUtils.isEmpty(packName) ? "Sticker Pack" : packName);

        // ── Publisher ──
        TextView publisherView = findViewById(R.id.info_publisher);
        if (!TextUtils.isEmpty(publisher)) {
            publisherView.setText(publisher);
            publisherView.setVisibility(View.VISIBLE);
        }

        // ── Sticker count ──
        ((TextView) findViewById(R.id.info_sticker_count)).setText(
                stickerCount + " sticker" + (stickerCount != 1 ? "s" : ""));

        // ── Total size ──
        ((TextView) findViewById(R.id.info_total_size)).setText(
                Formatter.formatShortFileSize(this, totalSize));

        // ── Pack type: detect by inspecting actual sticker files, not just the flag ──
        // The contents.json animatedStickerPack field can be wrong for some packs,
        // so we inspect the first sticker file directly.
        boolean isAnimated = detectPackAnimated(packId, stickerPack);
        ((TextView) findViewById(R.id.info_animated)).setText(isAnimated ? "Animated" : "Static");

        // ── Pack ID ──
        ((TextView) findViewById(R.id.info_pack_id)).setText(
                TextUtils.isEmpty(packId) ? "—" : packId);

        // ── Sticker list RecyclerView ──
        if (stickerPack != null) {
            List<Sticker> stickers = stickerPack.getStickers();
            if (stickers != null && !stickers.isEmpty()) {
                TextView header = findViewById(R.id.info_stickers_header);
                header.setText("Stickers (" + stickers.size() + ")");

                RecyclerView rv = findViewById(R.id.info_sticker_list);
                LinearLayoutManager lm = new LinearLayoutManager(this);
                rv.setLayoutManager(lm);
                rv.addItemDecoration(new DividerItemDecoration(this, lm.getOrientation()));
                rv.setAdapter(new StickerInfoAdapter(this, stickers, packId));
            }
        }

        // ── Publisher links (only visible if at least one is set) ──
        boolean hasLinks = !TextUtils.isEmpty(website) || !TextUtils.isEmpty(email)
                || !TextUtils.isEmpty(privacyPolicy) || !TextUtils.isEmpty(licenseAgreement);
        if (hasLinks) {
            findViewById(R.id.publisher_links_section).setVisibility(View.VISIBLE);
        }
        setupLink(website,          R.id.view_webpage,       () ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(website))));
        setupLink(email,            R.id.send_email,         () -> {
            Intent em = new Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", email, null));
            em.putExtra(Intent.EXTRA_EMAIL, new String[]{email});
            startActivity(Intent.createChooser(em, getString(R.string.info_send_email_to_prompt)));
        });
        setupLink(privacyPolicy,    R.id.privacy_policy,     () ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(privacyPolicy))));
        setupLink(licenseAgreement, R.id.license_agreement,  () ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(licenseAgreement))));
    }

    /**
     * Detect whether a pack is animated by inspecting the actual WebP files rather than
     * relying solely on the contents.json flag (which may be wrong for bot-generated packs).
     */
    private boolean detectPackAnimated(String packId, StickerPack pack) {
        if (pack == null || TextUtils.isEmpty(packId)) return false;
        // First check the flag — if true, trust it
        if (pack.animatedStickerPack) return true;
        // Flag says static — but verify by checking the actual first sticker file
        List<Sticker> stickers = pack.getStickers();
        if (stickers == null || stickers.isEmpty()) return false;
        // Try first sticker via WastickerParser's actual WebP inspection
        return WastickerParser.isAnimatedWebPPublic(this, packId, stickers.get(0).imageFileName);
    }

    private void setupLink(String value, int viewId, Runnable onClick) {
        TextView tv = findViewById(viewId);
        if (TextUtils.isEmpty(value)) {
            tv.setVisibility(View.GONE);
        } else {
            tv.setVisibility(View.VISIBLE);
            tv.setOnClickListener(v -> onClick.run());
        }
    }
}
