import pandas as pd

def preprocess_reviews(df):
    """
    리뷰 텍스트 길이, 별점 그룹 등을 계산해서 새로운 컬럼 추가
    """
    # 리뷰 길이 계산
    df['review_length'] = df['text'].str.len()

    # 리뷰 길이 그룹
    df['length_group'] = df['review_length'].apply(lambda x: 'Short' if x < 100 else 'Long')

    # 별점 그룹
    df['star_group'] = df['stars'].apply(lambda x: 'Low' if x <= 2 else ('High' if x >= 4 else 'Mid'))

    return df
