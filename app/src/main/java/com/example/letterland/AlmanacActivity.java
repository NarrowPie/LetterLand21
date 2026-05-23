package com.example.letterland;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AlmanacActivity extends AppCompatActivity {

    private RecyclerView rvAlmanac;
    private WordAdapter adapter;
    private boolean isShowingStarredOnly = false;

    // FIX: Implemented persistent thread executor pool to manage room database callbacks safely
    private ExecutorService almanacExecutor;

    @Override
    protected void onResume() {
        super.onResume();
        loadWordsFromDatabase();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_almanac);

        almanacExecutor = Executors.newSingleThreadExecutor();

        rvAlmanac = findViewById(R.id.rvAlmanac);
        ImageButton btnBack = findViewById(R.id.btnBackAlmanac);
        ImageButton btnFilterStarred = findViewById(R.id.btnFilterStarred);

        btnBack.setOnClickListener(v -> {
            SoundManager.getInstance(this).playClick();
            finish();
        });

        btnFilterStarred.setOnClickListener(v -> {
            SoundManager.getInstance(this).playClick();
            isShowingStarredOnly = !isShowingStarredOnly;

            if (isShowingStarredOnly) {
                btnFilterStarred.setImageResource(android.R.drawable.btn_star_big_on);
            } else {
                btnFilterStarred.setImageResource(android.R.drawable.btn_star_big_off);
            }

            loadWordsFromDatabase();
        });

        int screenWidthDp = getResources().getConfiguration().screenWidthDp;
        int columns = Math.max(2, screenWidthDp / 160);

        rvAlmanac.setLayoutManager(new GridLayoutManager(this, columns));
        adapter = new WordAdapter(new ArrayList<>());
        rvAlmanac.setAdapter(adapter);

        loadWordsFromDatabase();
    }

    private void loadWordsFromDatabase() {
        // FIX: Replaced raw thread instantiations with handled background configurations
        almanacExecutor.execute(() -> {
            String player = getSharedPreferences("LetterLandMemory", MODE_PRIVATE).getString("ACTIVE_PROFILE", "Default");

            List<WordEntry> myWords;
            // FIX: Initialized database using non-leaking getApplicationContext() framework tokens
            AppDatabase db = AppDatabase.getInstance(this.getApplicationContext());

            if (isShowingStarredOnly) {
                myWords = db.wordDao().getStarredWordsForProfile(player);
            } else {
                myWords = db.wordDao().getAllWordsForProfile(player);
            }

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                adapter.updateData(myWords);
            });
        });
    }

    @Override
    protected void onDestroy() {
        if (almanacExecutor != null) {
            almanacExecutor.shutdown();
        }
        super.onDestroy();
    }

    private class WordAdapter extends RecyclerView.Adapter<WordAdapter.WordViewHolder> {
        private List<WordEntry> words;

        public WordAdapter(List<WordEntry> words) {
            this.words = words;
        }

        public void updateData(List<WordEntry> newWords) {
            this.words = newWords;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public WordViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_word, parent, false);
            return new WordViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull WordViewHolder holder, int position) {
            WordEntry currentWord = words.get(position);
            holder.tvWord.setText(currentWord.word);

            // FIX: Implemented Glide to translate raw absolute path locations smoothly without blocking the UI thread
            if (currentWord.imagePath != null && !currentWord.imagePath.isEmpty()) {
                Glide.with(holder.ivImage.getContext())
                        .load(new File(currentWord.imagePath))
                        .placeholder(R.drawable.admin_pic)
                        .error(R.drawable.admin_pic)
                        .into(holder.ivImage);
            } else {
                holder.ivImage.setImageResource(R.drawable.admin_pic);
            }

            if (currentWord.isStarred) {
                holder.ivStar.setVisibility(View.VISIBLE);
            } else {
                holder.ivStar.setVisibility(View.GONE);
            }

            holder.itemView.setOnClickListener(v -> {
                SoundManager.getInstance(v.getContext()).playClick();
                android.content.Intent intent = new android.content.Intent(v.getContext(), WordDetailActivity.class);
                intent.putExtra("WORD_TEXT", currentWord.word);
                intent.putExtra("IMAGE_PATH", currentWord.imagePath);
                intent.putExtra("SOURCE_PAGE", "ALMANAC");
                v.getContext().startActivity(intent);
            });
        }

        @Override
        public int getItemCount() {
            return words.size();
        }

        class WordViewHolder extends RecyclerView.ViewHolder {
            ImageView ivImage;
            TextView tvWord;
            ImageView ivStar;

            public WordViewHolder(@NonNull View itemView) {
                super(itemView);
                ivImage = itemView.findViewById(R.id.ivGalleryImage);
                tvWord = itemView.findViewById(R.id.tvGalleryWord);
                ivStar = itemView.findViewById(R.id.ivStar);
            }
        }
    }
}