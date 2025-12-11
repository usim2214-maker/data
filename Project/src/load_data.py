import json
import pandas as pd

def load_reviews(file_path, n_rows=None):
    """
    Yelp 리뷰 데이터를 JSON에서 읽어와 DataFrame으로 반환
    n_rows: 최대 읽을 행 수
    """
    data = []
    with open(file_path, "r", encoding="utf-8") as f:
        for i, line in enumerate(f):
            if n_rows and i >= n_rows:
                break
            data.append(json.loads(line))
    return pd.DataFrame(data)
