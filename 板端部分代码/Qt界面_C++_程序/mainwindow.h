#ifndef MAINWINDOW_H
#define MAINWINDOW_H

#include <QLabel>
#include <QMainWindow>
#include <QMap>
#include <QElapsedTimer>
#include <QDialog>
#include <QProcess>
#include <QPushButton>
#include <QStringList>
#include <QStackedWidget>
#include <QToolButton>
#include <QUdpSocket>
#include <QVector>
#include <QtCharts>

QT_CHARTS_USE_NAMESPACE

class HeatMapWidget;
class RadarWidget;
class QTimer;
class QVBoxLayout;

class MainWindow : public QMainWindow {
    Q_OBJECT

public:
    explicit MainWindow(QWidget *parent = nullptr);
    ~MainWindow();
    void playEntranceAnimation();

private slots:
    void onStartClicked();
    void onPauseClicked();
    void onStopClicked();
    void processPendingDatagrams();
    void setActivePage(int index);
    void refreshClock();
    void toggleVideoFullScreen();
    void closeVideoFullScreen();

protected:
    void closeEvent(QCloseEvent *event) override;
    bool eventFilter(QObject *obj, QEvent *event) override;
    void keyPressEvent(QKeyEvent *event) override;

private:
    void setupUI();
    void setupChart();
    void setupNavigation(QVBoxLayout *layout);
    QWidget *createDashboardPage();
    QWidget *createInsightPage();
    QWidget *createHeatmapPage();
    QWidget *createWarningPage();
    QWidget *createSettingsPage();
    QWidget *createPanel(const QString &title, QWidget *content = nullptr);
    QWidget *createMetricCard(const QString &title, const QString &value, const QString &subText, const QString &accent);
    QToolButton *createNavButton(const QString &text, const QString &symbol);
    QPushButton *createControlButton(const QString &text, const QString &accent);
    QLabel *metricValueLabel(QWidget *card) const;
    QChartView *createChartView(QChart *chart) const;
    QChart *createBaseChart(const QString &title) const;
    void refreshNav();
    void seedDemoData();
    void updateCharts(QString data);
    void updateVisuals();
    void updateHistory(int focusScore, int total, int distracted, int fatigue, int phone);
    void resetAnalyticsState();
    void showChartPopup(QChartView *sourceView, const QString &title);
    void refreshChartPopup();
    void updateFullScreenFrame();
    void pushClassroomEvent(const QString &eventText);
    QString normalizeBehaviorKey(const QString &key) const;
    int behaviorValue(const QString &key) const;

    QWidget *shell = nullptr;
    QWidget *sideBar = nullptr;
    QWidget *topBar = nullptr;
    QStackedWidget *pageStack = nullptr;
    QLabel *pageTitleLabel = nullptr;
    QLabel *engineStateLabel = nullptr;
    QLabel *streamStateLabel = nullptr;
    QLabel *lastUpdateLabel = nullptr;

    QLabel *videoLabel = nullptr;
    QLabel *fullVideoLabel = nullptr;
    QLabel *totalCountLabel = nullptr;
    QLabel *focusScoreLabel = nullptr;
    QLabel *listeningMetricLabel = nullptr;
    QLabel *distractedMetricLabel = nullptr;
    QLabel *fatigueMetricLabel = nullptr;
    QLabel *phoneMetricLabel = nullptr;
    QLabel *stabilityMetricLabel = nullptr;
    QLabel *trendValueLabel = nullptr;
    QLabel *riskValueLabel = nullptr;

    QPushButton *btnStart = nullptr;
    QPushButton *btnPause = nullptr;
    QPushButton *btnStop = nullptr;
    QPushButton *btnVideoFullScreen = nullptr;
    QVector<QToolButton*> navButtons;

    QChartView *donutChartView = nullptr;
    QChartView *focusTrendView = nullptr;
    QChartView *behaviorBarView = nullptr;
    QChartView *scatterChartView = nullptr;
    QChartView *riskChartView = nullptr;

    QPieSeries *series = nullptr;
    QLineSeries *focusLineSeries = nullptr;
    QLineSeries *riskLineSeries = nullptr;
    QLineSeries *behaviorFocusSeries = nullptr;
    QLineSeries *behaviorEatingSeries = nullptr;
    QLineSeries *behaviorDeviceSeries = nullptr;
    QScatterSeries *scatterSeries = nullptr;
    QValueAxis *focusAxisX = nullptr;
    QValueAxis *focusAxisY = nullptr;
    QValueAxis *riskAxisX = nullptr;
    QValueAxis *riskAxisY = nullptr;
    QValueAxis *behaviorAxisX = nullptr;
    QValueAxis *behaviorAxisY = nullptr;
    QValueAxis *scatterAxisX = nullptr;
    QValueAxis *scatterAxisY = nullptr;
    HeatMapWidget *heatmapWidget = nullptr;
    RadarWidget *radarWidget = nullptr;
    QDialog *videoFullDialog = nullptr;
    QDialog *chartPopupDialog = nullptr;
    QPieSeries *popupPieSeries = nullptr;
    QLineSeries *popupFocusLineSeries = nullptr;
    QValueAxis *popupFocusAxisX = nullptr;
    QValueAxis *popupFocusAxisY = nullptr;
    QString chartPopupKind;
    QVector<QLabel*> eventLabels;
    QStringList eventRows;

    QUdpSocket *udpSocket = nullptr;
    QProcess *pythonProcess = nullptr;
    QTimer *clockTimer = nullptr;
    QElapsedTimer frameFpsTimer;
    QPixmap lastVideoPixmap;
    bool isPaused = false;
    bool compactMode = false;
    bool tinyMode = false;
    int currentPageIndex = 0;
    int sampleIndex = 0;
    int currentTotal = 0;
    int frameCounter = 0;
    QString lastEventSignature;
    QString selectedVideoSource = QStringLiteral("11");

    QMap<QString, int> currentBehaviors;
    QVector<int> focusHistory;
    QVector<int> totalHistory;
    QVector<int> distractedHistory;
    QVector<int> fatigueHistory;
    QVector<int> phoneHistory;
};

#endif
