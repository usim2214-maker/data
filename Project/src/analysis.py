import seaborn as sns
import matplotlib.pyplot as plt
from scipy.stats import mannwhitneyu

def plot_box(df, x_col, y_col, title):
    """
    Box plot 그리기
    """
    plt.figure(figsize=(8,6))
    sns.boxplot(x=x_col, y=y_col, data=df)
    plt.title(title)
    plt.show()

def mann_whitney_test(df, group_col, value_col, group1, group2):
    """
    두 그룹 간 Mann-Whitney U 테스트 수행
    """
    data1 = df[df[group_col] == group1][value_col]
    data2 = df[df[group_col] == group2][value_col]
    stat, p = mannwhitneyu(data1, data2, alternative='two-sided')
    return stat, p
