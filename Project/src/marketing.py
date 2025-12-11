import pandas as pd

def compute_user_marketing(df):
    """
    사용자별 마케팅 지표 계산 예시 (ROI 등)
    """
    # 사용자별 리뷰 수
    user_reviews = df.groupby("user_id").size().reset_index(name="review_count")

    # 사용자별 useful_votes 합
    user_votes = df.groupby("user_id")["useful"].sum().reset_index()

    # 합치기
    user_summary = pd.merge(user_reviews, user_votes, on="user_id")

    # ROI 예시 계산: useful_votes / review_count
    user_summary['roi'] = user_summary['useful'] / user_summary['review_count']

    return user_summary
