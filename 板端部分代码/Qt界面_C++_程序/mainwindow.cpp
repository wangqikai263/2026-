#include "mainwindow.h"

#include <QApplication>
#include <QCloseEvent>
#include <QComboBox>
#include <QCoreApplication>
#include <QDateTime>
#include <QDebug>
#include <QDialog>
#include <QDir>
#include <QEvent>
#include <QFileDialog>
#include <QFileInfo>
#include <QFrame>
#include <QGraphicsDropShadowEffect>
#include <QGridLayout>
#include <QHBoxLayout>
#include <QIcon>
#include <QImage>
#include <QKeyEvent>
#include <QList>
#include <QLineEdit>
#include <QMouseEvent>
#include <QNetworkDatagram>
#include <QPainter>
#include <QPainterPath>
#include <QPixmap>
#include <QProcessEnvironment>
#include <QPropertyAnimation>
#include <QParallelAnimationGroup>
#include <QPauseAnimation>
#include <QScreen>
#include <QSequentialAnimationGroup>
#include <QTimer>
#include <QtMath>
#include <QVBoxLayout>
#include <algorithm>
#include <cstdlib>

namespace {
const char *kBg = "#edf4f0";
const char *kInk = "#17211f";
const char *kMuted = "#6a7874";
const char *kLine = "#dce7e3";
const char *kGreen = "#1fb58f";
const char *kBlue = "#3478f6";
const char *kAmber = "#e7a02f";
const char *kRed = "#d95d55";
const int kHistoryLimit = 14;

QColor mixColor(const QColor &a, const QColor &b, qreal t)
{
    t = qBound<qreal>(0.0, t, 1.0);
    return QColor(a.red() + (b.red() - a.red()) * t,
                  a.green() + (b.green() - a.green()) * t,
                  a.blue() + (b.blue() - a.blue()) * t);
}

QString pillStyle(const QString &bg, const QString &fg)
{
    return QString("QLabel { background: %1; color: %2; border-radius: 12px; "
                   "padding: 5px 12px; font-size: 12px; font-weight: 600; }").arg(bg, fg);
}

bool isNightTheme(const QObject *owner)
{
    return owner && owner->property("themeKey").toString() == QStringLiteral("night");
}

QString themedPillStyle(const QObject *owner, const QString &tone)
{
    const bool night = isNightTheme(owner);
    if (tone == QStringLiteral("success")) {
        return pillStyle(night ? QStringLiteral("#16362e") : QStringLiteral("#e8f7f1"),
                         night ? QStringLiteral("#a8f4da") : QStringLiteral("#147b62"));
    }
    if (tone == QStringLiteral("warning")) {
        return pillStyle(night ? QStringLiteral("#3a2d17") : QStringLiteral("#fff0e2"),
                         night ? QStringLiteral("#ffd98b") : QStringLiteral("#a46b13"));
    }
    if (tone == QStringLiteral("error")) {
        return pillStyle(night ? QStringLiteral("#3a1d1f") : QStringLiteral("#ffecea"),
                         night ? QStringLiteral("#ffb9b3") : QStringLiteral("#b84f47"));
    }
    return pillStyle(night ? QStringLiteral("#17231f") : QStringLiteral("#edf2f0"),
                     night ? QStringLiteral("#eaf6f1") : QStringLiteral("#5c6b67"));
}

QString themedPillStyleForText(const QObject *owner, const QString &text)
{
    if (text.contains(QStringLiteral("异常"))) {
        return themedPillStyle(owner, QStringLiteral("error"));
    }
    if (text.contains(QStringLiteral("在线")) || text.contains(QStringLiteral("运行"))) {
        return themedPillStyle(owner, QStringLiteral("success"));
    }
    if (text.contains(QStringLiteral("启动")) || text.contains(QStringLiteral("暂停")) ||
        text.contains(QStringLiteral("冻结")) || text.contains(QStringLiteral("未绑定"))) {
        return themedPillStyle(owner, QStringLiteral("warning"));
    }
    return themedPillStyle(owner, QStringLiteral("neutral"));
}

QString videoPlaceholderStyle(const QObject *owner)
{
    const QString theme = owner ? owner->property("themeKey").toString() : QString();
    const bool night = theme == QStringLiteral("night");
    const bool coral = theme == QStringLiteral("coral");
    return QString(
        "QLabel { background: %1; color: %2; border: 1px solid %3; "
        "border-radius: 8px; font-size: 24px; font-weight: 700; }"
    ).arg(night ? QStringLiteral("#0f1715") : (coral ? QStringLiteral("#fffaf7") : QStringLiteral("#ffffff")),
          night ? QStringLiteral("#9fb3ad") : QStringLiteral("#8a9894"),
          night ? QStringLiteral("#22302d") : (coral ? QStringLiteral("#ecd2c8") : QStringLiteral("#e2ece8")));
}

void addSoftShadow(QWidget *widget, int blur = 28, int y = 8)
{
    QGraphicsDropShadowEffect *shadow = new QGraphicsDropShadowEffect(widget);
    shadow->setBlurRadius(blur);
    shadow->setOffset(0, y);
    shadow->setColor(QColor(31, 50, 46, 24));
    widget->setGraphicsEffect(shadow);
}

QPixmap glyphPixmap(const QString &glyph, int size, const QColor &color)
{
    QPixmap pixmap(size, size);
    pixmap.fill(Qt::transparent);

    QPainter painter(&pixmap);
    painter.setRenderHint(QPainter::Antialiasing, true);
    QFont font("Microsoft YaHei", int(size * 0.62), QFont::DemiBold);
    painter.setFont(font);
    painter.setPen(color);
    painter.drawText(pixmap.rect(), Qt::AlignCenter, glyph);
    return pixmap;
}

QIcon navGlyphIcon(const QString &glyph, int size)
{
    QIcon icon;
    icon.addPixmap(glyphPixmap(glyph, size, QColor("#65736f")), QIcon::Normal, QIcon::Off);
    icon.addPixmap(glyphPixmap(glyph, size, QColor("#17211f")), QIcon::Active, QIcon::Off);
    icon.addPixmap(glyphPixmap(glyph, size, QColor("#ffffff")), QIcon::Normal, QIcon::On);
    icon.addPixmap(glyphPixmap(glyph, size, QColor("#ffffff")), QIcon::Active, QIcon::On);
    return icon;
}

QRect scaledAroundCenter(const QRect &rect, qreal scale, int yOffset = 0)
{
    const QSize size(qMax(1, int(rect.width() * scale)),
                     qMax(1, int(rect.height() * scale)));
    QPoint topLeft(rect.center().x() - size.width() / 2,
                   rect.center().y() - size.height() / 2 + yOffset);
    return QRect(topLeft, size);
}
}

class HeatMapWidget : public QWidget {
public:
    explicit HeatMapWidget(QWidget *parent = nullptr)
        : QWidget(parent)
    {
        const int screenH = QApplication::primaryScreen()
            ? QApplication::primaryScreen()->availableGeometry().height()
            : 720;
        setMinimumHeight(screenH <= 600 ? 170 : (screenH <= 760 ? 220 : 280));
        setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Expanding);
        setProfile(82, 10, 5, 3);
    }

    void setProfile(int focus, int distracted, int fatigue, int phone)
    {
        m_values.clear();
        const int risk = qBound(0, distracted * 4 + fatigue * 5 + phone * 7, 100);
        for (int row = 0; row < 6; ++row) {
            for (int col = 0; col < 8; ++col) {
                int wave = ((row + 2) * 13 + (col + 3) * 9 + row * col * 4) % 23;
                int edgePenalty = (row == 0 || row == 5 || col == 0 || col == 7) ? 7 : 0;
                int value = focus - risk / 4 + wave - edgePenalty;
                if ((row == 2 || row == 3) && (col == 3 || col == 4)) {
                    value += 9;
                }
                m_values.append(qBound(18, value, 98));
            }
        }
        update();
    }

protected:
    void paintEvent(QPaintEvent *) override
    {
        const bool night = isNightTheme(window());
        const QColor titleColor(night ? "#eaf6f1" : kInk);
        const QColor cellTextColor(night ? "#0b1110" : "#192724");
        QPainter painter(this);
        painter.setRenderHint(QPainter::Antialiasing, true);

        QRectF area = rect().adjusted(22, 18, -22, -20);
        QFont titleFont("Microsoft YaHei", 10, QFont::DemiBold);
        painter.setFont(titleFont);
        painter.setPen(titleColor);
        painter.drawText(area.left(), area.top(), QStringLiteral("座位专注热力"));

        QRectF grid = area.adjusted(0, 34, 0, 0);
        const qreal gap = 8.0;
        const qreal cellW = (grid.width() - gap * 7) / 8.0;
        const qreal cellH = (grid.height() - gap * 5) / 6.0;

        QFont cellFont("Microsoft YaHei", 8, QFont::DemiBold);
        painter.setFont(cellFont);

        for (int row = 0; row < 6; ++row) {
            for (int col = 0; col < 8; ++col) {
                const int index = row * 8 + col;
                const int value = index < m_values.size() ? m_values[index] : 60;
                QRectF cell(grid.left() + col * (cellW + gap),
                            grid.top() + row * (cellH + gap),
                            cellW,
                            cellH);
                QColor color;
                if (value < 55) {
                    color = mixColor(QColor("#f6ddd6"), QColor("#f0a39a"), value / 55.0);
                } else {
                    color = mixColor(QColor("#dceee8"), QColor("#1fb58f"), (value - 55) / 45.0);
                }

                painter.setPen(Qt::NoPen);
                painter.setBrush(color);
                painter.drawRoundedRect(cell, 7, 7);
                painter.setPen(QColor(cellTextColor.red(), cellTextColor.green(), cellTextColor.blue(),
                                      value > 75 ? 220 : 165));
                painter.drawText(cell, Qt::AlignCenter, QString::number(value));
            }
        }
    }

private:
    QVector<int> m_values;
};

class RadarWidget : public QWidget {
public:
    explicit RadarWidget(QWidget *parent = nullptr)
        : QWidget(parent)
    {
        const int screenH = QApplication::primaryScreen()
            ? QApplication::primaryScreen()->availableGeometry().height()
            : 720;
        setMinimumHeight(screenH <= 600 ? 170 : (screenH <= 760 ? 210 : 260));
        setScores(82, 66, 54, 40, 72);
    }

    void setScores(int focus, int interaction, int stability, int fatigueControl, int deviceControl)
    {
        m_scores = QVector<int>() << focus << interaction << stability << fatigueControl << deviceControl;
        update();
    }

protected:
    void paintEvent(QPaintEvent *) override
    {
        const bool night = isNightTheme(window());
        const QColor gridColor(night ? "#2b3d39" : kLine);
        const QColor labelColor(night ? "#aebfb9" : kMuted);
        QPainter painter(this);
        painter.setRenderHint(QPainter::Antialiasing, true);
        QRectF area = rect().adjusted(18, 18, -18, -18);
        QPointF center = area.center() + QPointF(0, 8);
        qreal radius = qMin(area.width(), area.height()) * 0.34;
        QStringList labels;
        labels << QStringLiteral("专注") << QStringLiteral("互动") << QStringLiteral("稳定")
               << QStringLiteral("饮食") << QStringLiteral("设备");

        painter.setPen(QPen(gridColor, 1));
        for (int ring = 1; ring <= 4; ++ring) {
            QPolygonF polygon;
            qreal ringR = radius * ring / 4.0;
            for (int i = 0; i < labels.size(); ++i) {
                qreal angle = -90.0 + i * 360.0 / labels.size();
                qreal rad = angle * 3.14159265358979323846 / 180.0;
                polygon << QPointF(center.x() + qCos(rad) * ringR,
                                   center.y() + qSin(rad) * ringR);
            }
            painter.drawPolygon(polygon);
        }

        QPainterPath scorePath;
        for (int i = 0; i < labels.size(); ++i) {
            int score = i < m_scores.size() ? m_scores[i] : 50;
            qreal angle = -90.0 + i * 360.0 / labels.size();
            qreal rad = angle * 3.14159265358979323846 / 180.0;
            qreal scoreR = radius * qBound(0, score, 100) / 100.0;
            QPointF p(center.x() + qCos(rad) * scoreR, center.y() + qSin(rad) * scoreR);
            if (i == 0) {
                scorePath.moveTo(p);
            } else {
                scorePath.lineTo(p);
            }
        }
        scorePath.closeSubpath();
        painter.setPen(QPen(QColor(kGreen), 2));
        painter.setBrush(QColor(31, 181, 143, 54));
        painter.drawPath(scorePath);

        QFont labelFont("Microsoft YaHei", 9, QFont::DemiBold);
        painter.setFont(labelFont);
        painter.setPen(labelColor);
        for (int i = 0; i < labels.size(); ++i) {
            qreal angle = -90.0 + i * 360.0 / labels.size();
            qreal rad = angle * 3.14159265358979323846 / 180.0;
            QPointF p(center.x() + qCos(rad) * (radius + 28),
                      center.y() + qSin(rad) * (radius + 28));
            QRectF labelRect(p.x() - 34, p.y() - 10, 68, 20);
            painter.drawText(labelRect, Qt::AlignCenter, labels[i]);
        }
    }

private:
    QVector<int> m_scores;
};

MainWindow::MainWindow(QWidget *parent) : QMainWindow(parent)
{
    setupUI();
    setupChart();
    frameFpsTimer.start();

    clockTimer = new QTimer(this);
    connect(clockTimer, &QTimer::timeout, this, &MainWindow::refreshClock);
    clockTimer->start(1000);
    refreshClock();

    udpSocket = new QUdpSocket(this);
    bool isBound = udpSocket->bind(QHostAddress("127.0.0.1"), 9999,
                                   QUdpSocket::ShareAddress | QUdpSocket::ReuseAddressHint);
    if (isBound) {
        qDebug() << ">>> [网络探针] UDP 9999 端口绑定成功，等待接收数据...";
        streamStateLabel->setText(QStringLiteral("数据通道在线"));
        streamStateLabel->setStyleSheet(themedPillStyle(this, QStringLiteral("success")));
    } else {
        qDebug() << ">>> [网络探针] UDP 9999 端口绑定失败";
        streamStateLabel->setText(QStringLiteral("数据通道未绑定"));
        streamStateLabel->setStyleSheet(themedPillStyle(this, QStringLiteral("warning")));
    }

    connect(udpSocket, &QUdpSocket::readyRead, this, &MainWindow::processPendingDatagrams);

    pythonProcess = new QProcess(this);
    connect(pythonProcess, &QProcess::errorOccurred, [this](QProcess::ProcessError error) {
        qDebug() << ">>> [引擎探针] Python 启动失败，错误码:" << error;
        engineStateLabel->setText(QStringLiteral("引擎异常"));
        engineStateLabel->setStyleSheet(themedPillStyle(this, QStringLiteral("error")));
    });
    connect(pythonProcess, &QProcess::readyReadStandardOutput, [this]() {
        qDebug() << ">>> [Python 输出]:" << pythonProcess->readAllStandardOutput().trimmed();
    });
    connect(pythonProcess, &QProcess::readyReadStandardError, [this]() {
        qDebug() << ">>> [Python 错误]:" << pythonProcess->readAllStandardError().trimmed();
    });
}

MainWindow::~MainWindow()
{
    if (pythonProcess && pythonProcess->state() == QProcess::Running) {
        pythonProcess->kill();
        pythonProcess->waitForFinished();
    }
}

void MainWindow::playEntranceAnimation()
{
    if (!pageStack || !pageStack->currentWidget()) return;

    QTimer::singleShot(0, this, [this]() {
        QWidget *page = pageStack->currentWidget();
        QList<QFrame*> frames = page->findChildren<QFrame*>();
        QList<QWidget*> modules;
        for (QFrame *frame : frames) {
            const QString name = frame->objectName();
            if (!(name == "panel" || name == "metricCard" || name == "videoPanel")) {
                continue;
            }

            bool nestedInModule = false;
            for (QWidget *parent = frame->parentWidget(); parent && parent != page; parent = parent->parentWidget()) {
                const QString parentName = parent->objectName();
                if (parentName == "panel" || parentName == "metricCard" || parentName == "videoPanel") {
                    nestedInModule = true;
                    break;
                }
            }

            if (!nestedInModule && frame->isVisible() && frame->geometry().isValid()) {
                modules.append(frame);
            }
        }
        if (modules.isEmpty()) return;

        std::sort(modules.begin(), modules.end(), [](QWidget *a, QWidget *b) {
            if (a->y() == b->y()) return a->x() < b->x();
            return a->y() < b->y();
        });

        QParallelAnimationGroup *group = new QParallelAnimationGroup(this);
        QList<QPair<QWidget*, QRect>> finals;
        for (int i = 0; i < modules.size(); ++i) {
            QWidget *module = modules[i];
            const QRect finalRect = module->geometry();
            finals.append(qMakePair(module, finalRect));
            const QRect startRect = scaledAroundCenter(finalRect, 0.985, 3);
            module->setGeometry(startRect);
            module->raise();

            QPropertyAnimation *pop = new QPropertyAnimation(module, "geometry");
            pop->setDuration(680);
            pop->setStartValue(startRect);
            pop->setKeyValueAt(0.62, scaledAroundCenter(finalRect, 1.006, 0));
            pop->setKeyValueAt(0.82, scaledAroundCenter(finalRect, 0.998, 0));
            pop->setEndValue(finalRect);
            pop->setEasingCurve(QEasingCurve::OutCubic);

            QSequentialAnimationGroup *sequence = new QSequentialAnimationGroup(group);
            sequence->addPause(qMin(90, i * 14));
            sequence->addAnimation(pop);
            group->addAnimation(sequence);
        }

        connect(group, &QParallelAnimationGroup::finished, [group, finals]() {
            for (const QPair<QWidget*, QRect> &item : finals) {
                if (item.first) item.first->setGeometry(item.second);
            }
            group->deleteLater();
        });
        group->start();
    });
}

void MainWindow::setupUI()
{
    setWindowTitle(QStringLiteral("智眸 - 课堂行为智能分析"));
    setWindowFlags(Qt::FramelessWindowHint);

    const QSize screenSize = QApplication::primaryScreen()
        ? QApplication::primaryScreen()->availableGeometry().size()
        : QSize(1280, 720);
    compactMode = screenSize.width() <= 1280 || screenSize.height() <= 760;
    tinyMode = screenSize.width() <= 1024 || screenSize.height() <= 600;

    resize(qMin(1440, screenSize.width()), qMin(900, screenSize.height()));
    const QSize minWindowSize = tinyMode
        ? QSize(qMin(760, screenSize.width()), qMin(460, screenSize.height()))
        : QSize(qMin(960, screenSize.width()), qMin(560, screenSize.height()));
    setMinimumSize(minWindowSize);

    const int sideWidth = tinyMode ? 70 : (compactMode ? 82 : 96);
    const int sideMarginX = tinyMode ? 9 : (compactMode ? 11 : 14);
    const int sideMarginY = tinyMode ? 12 : (compactMode ? 16 : 24);
    const int sideSpacing = tinyMode ? 8 : (compactMode ? 12 : 16);
    const int logoW = tinyMode ? 52 : (compactMode ? 58 : 68);
    const int logoH = tinyMode ? 54 : (compactMode ? 62 : 72);
    const int workspaceX = tinyMode ? 10 : (compactMode ? 16 : 28);
    const int workspaceTop = tinyMode ? 8 : (compactMode ? 12 : 18);
    const int workspaceBottom = tinyMode ? 10 : (compactMode ? 14 : 24);
    const int workspaceSpacing = tinyMode ? 10 : (compactMode ? 14 : 18);
    const int topBarHeight = tinyMode ? 44 : (compactMode ? 50 : 58);
    const int titleSize = tinyMode ? 20 : (compactMode ? 23 : 26);
    setStyleSheet(QString(
        "QMainWindow { background: %1; }"
        "QLabel { color: %2; font-family: 'Microsoft YaHei'; }"
        "QPushButton { font-family: 'Microsoft YaHei'; }"
        "QToolTip { background: #17211f; color: white; border: 0; padding: 6px; }"
    ).arg(kBg, kInk));

    shell = new QWidget(this);
    setCentralWidget(shell);

    QHBoxLayout *root = new QHBoxLayout(shell);
    root->setContentsMargins(0, 0, 0, 0);
    root->setSpacing(0);

    sideBar = new QWidget(shell);
    sideBar->setFixedWidth(sideWidth);
    sideBar->setStyleSheet(
        "QWidget { background: #f8fbf9; border-right: 1px solid #dce7e3; }"
    );
    QVBoxLayout *sideLayout = new QVBoxLayout(sideBar);
    sideLayout->setContentsMargins(sideMarginX, sideMarginY, sideMarginX, sideMarginY);
    sideLayout->setSpacing(sideSpacing);

    QLabel *logo = new QLabel(QStringLiteral("智\n眸"), sideBar);
    logo->setObjectName("brandLogo");
    logo->setAlignment(Qt::AlignCenter);
    logo->setFixedSize(logoW, logoH);
    logo->setStyleSheet(QString(
        "QLabel { background: #17211f; color: #f4fbf8; border-radius: 18px; "
        "font-size: %1px; font-weight: 800; line-height: 26px; }"
    ).arg(tinyMode ? 17 : (compactMode ? 19 : 22)));
    sideLayout->addWidget(logo, 0, Qt::AlignHCenter);
    sideLayout->addSpacing(tinyMode ? 2 : 10);
    setupNavigation(sideLayout);
    sideLayout->addStretch();

    QPushButton *resetButton = new QPushButton(QStringLiteral("复位"), sideBar);
    resetButton->setObjectName("resetButton");
    resetButton->setCursor(Qt::PointingHandCursor);
    resetButton->setFixedHeight(tinyMode ? 30 : 34);
    resetButton->setStyleSheet(
        "QPushButton { color: #5f6f6b; background: #ffffff; border: 1px solid #dce7e3; "
        "border-radius: 9px; font-size: 12px; font-weight: 800; }"
        "QPushButton:hover { background: #e8f7f1; color: #147b62; border-color: #bfe5d8; }"
    );
    connect(resetButton, &QPushButton::clicked, [this]() {
        resetAnalyticsState();
    });
    sideLayout->addWidget(resetButton);

    QWidget *workspace = new QWidget(shell);
    QVBoxLayout *workspaceLayout = new QVBoxLayout(workspace);
    workspaceLayout->setContentsMargins(workspaceX, workspaceTop, workspaceX, workspaceBottom);
    workspaceLayout->setSpacing(workspaceSpacing);

    topBar = new QWidget(workspace);
    topBar->setFixedHeight(topBarHeight);
    QHBoxLayout *topLayout = new QHBoxLayout(topBar);
    topLayout->setContentsMargins(0, 0, 0, 0);
    topLayout->setSpacing(12);

    pageTitleLabel = new QLabel(QStringLiteral("实况中枢"), topBar);
    pageTitleLabel->setStyleSheet(QString(
        "QLabel { color: #17211f; font-size: %1px; font-weight: 800; }"
    ).arg(titleSize));
    topLayout->addWidget(pageTitleLabel);

    QLabel *subTitle = new QLabel(QStringLiteral("AI 课堂行为洞察 / RK3588 NPU"), topBar);
    subTitle->setStyleSheet("QLabel { color: #7b8985; font-size: 13px; }");
    if (!tinyMode) {
        topLayout->addWidget(subTitle);
    } else {
        subTitle->hide();
    }
    topLayout->addStretch();

    engineStateLabel = new QLabel(QStringLiteral("引擎待命"), topBar);
    engineStateLabel->setStyleSheet(themedPillStyle(this, QStringLiteral("neutral")));
    streamStateLabel = new QLabel(QStringLiteral("数据通道检测中"), topBar);
    streamStateLabel->setStyleSheet(themedPillStyle(this, QStringLiteral("neutral")));
    lastUpdateLabel = new QLabel(QStringLiteral("更新 --"), topBar);
    lastUpdateLabel->setStyleSheet(themedPillStyle(this, QStringLiteral("neutral")));
    topLayout->addWidget(engineStateLabel);
    topLayout->addWidget(streamStateLabel);
    topLayout->addWidget(lastUpdateLabel);

    QPushButton *minimizeButton = new QPushButton(QStringLiteral("—"), topBar);
    QPushButton *closeButton = new QPushButton(QStringLiteral("×"), topBar);
    const QString windowButtonStyle =
        "QPushButton { background: transparent; color: #6a7874; border: 0; "
        "font-size: 20px; font-weight: 700; border-radius: 8px; }"
        "QPushButton:hover { background: #e5eeea; color: #17211f; }";
    const int windowButtonSize = tinyMode ? 28 : 34;
    minimizeButton->setFixedSize(windowButtonSize, windowButtonSize);
    closeButton->setFixedSize(windowButtonSize, windowButtonSize);
    minimizeButton->setStyleSheet(windowButtonStyle);
    closeButton->setStyleSheet(windowButtonStyle +
                               "QPushButton:hover { background: #ffe8e5; color: #d95d55; }");
    connect(minimizeButton, &QPushButton::clicked, this, &MainWindow::showMinimized);
    minimizeButton->setCursor(Qt::PointingHandCursor);
    closeButton->setCursor(Qt::PointingHandCursor);
    connect(closeButton, &QPushButton::clicked, [this]() {
        onStopClicked();
        qApp->quit();
    });
    topLayout->addWidget(minimizeButton);
    topLayout->addWidget(closeButton);

    pageStack = new QStackedWidget(workspace);
    pageStack->setStyleSheet("QStackedWidget { background: transparent; }");
    pageStack->addWidget(createDashboardPage());
    pageStack->addWidget(createInsightPage());
    pageStack->addWidget(createHeatmapPage());
    pageStack->addWidget(createWarningPage());
    pageStack->addWidget(createSettingsPage());

    workspaceLayout->addWidget(topBar);
    workspaceLayout->addWidget(pageStack, 1);

    root->addWidget(sideBar);
    root->addWidget(workspace, 1);
    refreshNav();
}

void MainWindow::setupNavigation(QVBoxLayout *layout)
{
    const QStringList names = QStringList()
        << QStringLiteral("总览")
        << QStringLiteral("洞察")
        << QStringLiteral("热力")
        << QStringLiteral("预警")
        << QStringLiteral("设置");
    const QStringList symbols = QStringList()
        << QStringLiteral("●")
        << QStringLiteral("✦")
        << QStringLiteral("▦")
        << QStringLiteral("△")
        << QStringLiteral("⚙");

    for (int i = 0; i < names.size(); ++i) {
        QToolButton *button = createNavButton(names[i], symbols[i]);
        navButtons.append(button);
        connect(button, &QToolButton::clicked, [this, i]() { setActivePage(i); });
        layout->addWidget(button);
    }
}

QToolButton *MainWindow::createNavButton(const QString &text, const QString &symbol)
{
    QToolButton *button = new QToolButton(sideBar);
    button->setText(text);
    button->setToolButtonStyle(Qt::ToolButtonTextUnderIcon);
    button->setCheckable(true);
    button->setCursor(Qt::PointingHandCursor);
    const int buttonW = tinyMode ? 54 : (compactMode ? 62 : 70);
    const int buttonH = tinyMode ? 54 : (compactMode ? 62 : 70);
    const int iconSize = tinyMode ? 23 : (compactMode ? 27 : 31);
    const int fontSize = tinyMode ? 12 : (compactMode ? 13 : 14);
    button->setFixedSize(buttonW, buttonH);
    button->setIcon(navGlyphIcon(symbol, iconSize));
    button->setIconSize(QSize(iconSize, iconSize));
    button->setStyleSheet(QString(
        "QToolButton { background: transparent; color: #65736f; border: 0; "
        "border-radius: 16px; font-size: %1px; font-weight: 800; text-align: center; padding-top: 3px; }"
        "QToolButton:hover { background: #edf4f0; color: #17211f; }"
        "QToolButton:checked { background: #17211f; color: #ffffff; }"
    ).arg(fontSize));
    return button;
}

QPushButton *MainWindow::createControlButton(const QString &text, const QString &accent)
{
    QPushButton *button = new QPushButton(text);
    button->setCursor(Qt::PointingHandCursor);
    button->setMinimumHeight(tinyMode ? 34 : (compactMode ? 38 : 42));
    button->setStyleSheet(QString(
        "QPushButton { background: %1; color: white; border: 0; border-radius: 10px; "
        "padding: 0 %2px; font-size: %3px; font-weight: 800; }"
        "QPushButton:hover { background: %4; }"
        "QPushButton:pressed { background: #17211f; }"
    ).arg(accent,
          QString::number(tinyMode ? 10 : 18),
          QString::number(tinyMode ? 12 : 14),
          mixColor(QColor(accent), QColor("#17211f"), 0.12).name()));
    return button;
}

QWidget *MainWindow::createDashboardPage()
{
    QWidget *page = new QWidget(pageStack);
    QHBoxLayout *layout = new QHBoxLayout(page);
    layout->setContentsMargins(0, 0, 0, 0);
    layout->setSpacing(tinyMode ? 10 : 18);

    QVBoxLayout *left = new QVBoxLayout();
    left->setSpacing(tinyMode ? 8 : 14);

    QFrame *videoPanel = new QFrame(page);
    videoPanel->setObjectName("videoPanel");
    videoPanel->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Expanding);
    videoPanel->setStyleSheet(
        "QFrame#videoPanel { background: #ffffff; border-radius: 8px; border: 1px solid #d7e4df; }"
    );
    addSoftShadow(videoPanel, 30, 8);
    QVBoxLayout *videoLayout = new QVBoxLayout(videoPanel);
    videoLayout->setContentsMargins(tinyMode ? 10 : 14, tinyMode ? 10 : 14,
                                    tinyMode ? 10 : 14, tinyMode ? 10 : 14);
    videoLayout->setSpacing(tinyMode ? 7 : 10);

    QHBoxLayout *videoTitle = new QHBoxLayout();
    QLabel *liveTitle = new QLabel(QStringLiteral("实时视觉流"), videoPanel);
    liveTitle->setStyleSheet(QString("QLabel { color: #17211f; font-size: %1px; font-weight: 800; }")
                             .arg(tinyMode ? 14 : 18));
    btnVideoFullScreen = new QPushButton(QStringLiteral("全屏查看"), videoPanel);
    btnVideoFullScreen->setObjectName("videoFullScreenButton");
    btnVideoFullScreen->setCursor(Qt::PointingHandCursor);
    btnVideoFullScreen->setFixedHeight(tinyMode ? 28 : 32);
    btnVideoFullScreen->setStyleSheet(
        "QPushButton { background: #f6faf8; color: #40504c; border: 1px solid #dce7e3; "
        "border-radius: 8px; padding: 0 12px; font-size: 12px; font-weight: 700; }"
        "QPushButton:hover { background: #17211f; color: white; border-color: #17211f; }"
    );
    connect(btnVideoFullScreen, &QPushButton::clicked, this, &MainWindow::toggleVideoFullScreen);
    videoTitle->addWidget(liveTitle);
    videoTitle->addStretch();
    videoTitle->addWidget(btnVideoFullScreen);
    videoLayout->addLayout(videoTitle);

    videoLabel = new QLabel(QStringLiteral("点击“开始分析”接入课堂画面"), videoPanel);
    videoLabel->setAlignment(Qt::AlignCenter);
    videoLabel->setMinimumHeight(tinyMode ? 210 : (compactMode ? 300 : 420));
    videoLabel->setSizePolicy(QSizePolicy::Ignored, QSizePolicy::Ignored);
    videoLabel->setScaledContents(true);
    videoLabel->setStyleSheet(videoPlaceholderStyle(this));
    videoLayout->addWidget(videoLabel, 1);

    QHBoxLayout *controlLayout = new QHBoxLayout();
    controlLayout->setSpacing(tinyMode ? 6 : 10);
    btnStart = createControlButton(QStringLiteral("开始分析"), kGreen);
    btnPause = createControlButton(QStringLiteral("暂停画面"), kAmber);
    btnStop = createControlButton(QStringLiteral("结束分析"), kRed);
    controlLayout->addWidget(btnStart);
    controlLayout->addWidget(btnPause);
    controlLayout->addWidget(btnStop);
    videoLayout->addLayout(controlLayout);
    left->addWidget(videoPanel, 7);

    focusLineSeries = new QLineSeries();
    focusLineSeries->setName(QStringLiteral("专注度"));
    focusLineSeries->setColor(QColor(kGreen));
    QPen focusPen(QColor(kGreen), 3);
    focusPen.setCapStyle(Qt::RoundCap);
    focusLineSeries->setPen(focusPen);
    QChart *focusChart = createBaseChart(QStringLiteral("实时趋势"));
    focusChart->setTitle("");
    focusChart->legend()->hide();
    focusChart->addSeries(focusLineSeries);
    focusAxisX = new QValueAxis();
    focusAxisY = new QValueAxis();
    focusAxisX->setRange(0, kHistoryLimit - 1);
    focusAxisY->setRange(0, 100);
    focusAxisX->setLabelsVisible(false);
    focusAxisY->setLabelFormat("%d");
    focusAxisY->setGridLineColor(QColor("#edf2f0"));
    focusChart->addAxis(focusAxisX, Qt::AlignBottom);
    focusChart->addAxis(focusAxisY, Qt::AlignLeft);
    focusLineSeries->attachAxis(focusAxisX);
    focusLineSeries->attachAxis(focusAxisY);
    focusTrendView = createChartView(focusChart);
    focusTrendView->installEventFilter(this);
    focusTrendView->viewport()->installEventFilter(this);
    focusTrendView->setCursor(Qt::PointingHandCursor);
    focusTrendView->setToolTip(QStringLiteral("点击放大专注曲线"));
    left->addWidget(createPanel(QStringLiteral("专注曲线"), focusTrendView), 2);

    QVBoxLayout *right = new QVBoxLayout();
    right->setSpacing(tinyMode ? 8 : 14);

    QGridLayout *metricGrid = new QGridLayout();
    metricGrid->setSpacing(tinyMode ? 8 : 14);
    QWidget *totalCard = createMetricCard(QStringLiteral("识别人数"), QStringLiteral("--"), QStringLiteral("实时检测目标"), kGreen);
    totalCountLabel = metricValueLabel(totalCard);
    QWidget *focusCard = createMetricCard(QStringLiteral("专注度"), QStringLiteral("--"), QStringLiteral("听讲 / 阅读 / 书写"), kBlue);
    focusScoreLabel = metricValueLabel(focusCard);
    QWidget *listeningCard = createMetricCard(QStringLiteral("专注行为"), QStringLiteral("--"), QStringLiteral("听讲、阅读、书写"), kGreen);
    listeningMetricLabel = metricValueLabel(listeningCard);
    QWidget *distractedCard = createMetricCard(QStringLiteral("偏离行为"), QStringLiteral("--"), QStringLiteral("饮食、手机、平板、电脑"), kRed);
    distractedMetricLabel = metricValueLabel(distractedCard);
    metricGrid->addWidget(totalCard, 0, 0);
    metricGrid->addWidget(focusCard, 0, 1);
    metricGrid->addWidget(listeningCard, 1, 0);
    metricGrid->addWidget(distractedCard, 1, 1);
    right->addLayout(metricGrid, 2);

    series = new QPieSeries();
    series->setHoleSize(0.0);
    series->setPieSize(tinyMode ? 0.62 : 0.68);
    QChart *donutChart = createBaseChart(QStringLiteral("行为构成"));
    donutChart->setTitle("");
    donutChart->addSeries(series);
    donutChart->legend()->setVisible(true);
    donutChart->legend()->setAlignment(Qt::AlignRight);
    donutChartView = createChartView(donutChart);
    donutChartView->installEventFilter(this);
    donutChartView->viewport()->installEventFilter(this);
    donutChartView->setCursor(Qt::PointingHandCursor);
    donutChartView->setToolTip(QStringLiteral("点击放大分布矩阵"));
    right->addWidget(createPanel(QStringLiteral("分布矩阵"), donutChartView), 8);

    layout->addLayout(left, 7);
    layout->addLayout(right, 4);

    connect(btnStart, &QPushButton::clicked, this, &MainWindow::onStartClicked);
    connect(btnPause, &QPushButton::clicked, this, &MainWindow::onPauseClicked);
    connect(btnStop, &QPushButton::clicked, this, &MainWindow::onStopClicked);
    return page;
}

QWidget *MainWindow::createInsightPage()
{
    QWidget *page = new QWidget(pageStack);
    QVBoxLayout *layout = new QVBoxLayout(page);
    layout->setContentsMargins(0, 0, 0, 0);
    layout->setSpacing(tinyMode ? 10 : 18);

    QHBoxLayout *top = new QHBoxLayout();
    top->setSpacing(tinyMode ? 8 : 14);
    QWidget *fatigueCard = createMetricCard(QStringLiteral("饮食行为"), QStringLiteral("--"), QStringLiteral("饮食"), kAmber);
    fatigueMetricLabel = metricValueLabel(fatigueCard);
    QWidget *phoneCard = createMetricCard(QStringLiteral("设备行为"), QStringLiteral("--"), QStringLiteral("手机、平板、电脑"), kRed);
    phoneMetricLabel = metricValueLabel(phoneCard);
    QWidget *coherenceCard = createMetricCard(QStringLiteral("课堂稳定"), QStringLiteral("--"), QStringLiteral("7 类识别结果综合"), kGreen);
    stabilityMetricLabel = metricValueLabel(coherenceCard);
    top->addWidget(fatigueCard);
    top->addWidget(phoneCard);
    top->addWidget(coherenceCard);
    layout->addLayout(top, 1);

    behaviorFocusSeries = new QLineSeries();
    behaviorEatingSeries = new QLineSeries();
    behaviorDeviceSeries = new QLineSeries();
    behaviorFocusSeries->setName(QStringLiteral("听讲 / 阅读 / 书写"));
    behaviorEatingSeries->setName(QStringLiteral("饮食"));
    behaviorDeviceSeries->setName(QStringLiteral("使用设备"));

    QPen behaviorFocusPen(QColor(kGreen), 3);
    QPen behaviorEatingPen(QColor(kAmber), 3);
    QPen behaviorDevicePen(QColor(kRed), 3);
    behaviorFocusPen.setCapStyle(Qt::RoundCap);
    behaviorEatingPen.setCapStyle(Qt::RoundCap);
    behaviorDevicePen.setCapStyle(Qt::RoundCap);
    behaviorFocusSeries->setPen(behaviorFocusPen);
    behaviorEatingSeries->setPen(behaviorEatingPen);
    behaviorDeviceSeries->setPen(behaviorDevicePen);

    QChart *behaviorChart = createBaseChart(QStringLiteral("行为连续曲线"));
    behaviorChart->addSeries(behaviorFocusSeries);
    behaviorChart->addSeries(behaviorEatingSeries);
    behaviorChart->addSeries(behaviorDeviceSeries);
    behaviorChart->legend()->setVisible(true);
    behaviorChart->legend()->setAlignment(Qt::AlignBottom);
    behaviorAxisX = new QValueAxis();
    behaviorAxisY = new QValueAxis();
    behaviorAxisX->setLabelsVisible(false);
    behaviorAxisX->setRange(0, kHistoryLimit - 1);
    behaviorAxisY->setRange(0, 10);
    behaviorAxisY->setLabelFormat("%d");
    behaviorAxisY->setGridLineColor(QColor("#edf2f0"));
    behaviorChart->addAxis(behaviorAxisX, Qt::AlignBottom);
    behaviorChart->addAxis(behaviorAxisY, Qt::AlignLeft);
    behaviorFocusSeries->attachAxis(behaviorAxisX);
    behaviorFocusSeries->attachAxis(behaviorAxisY);
    behaviorEatingSeries->attachAxis(behaviorAxisX);
    behaviorEatingSeries->attachAxis(behaviorAxisY);
    behaviorDeviceSeries->attachAxis(behaviorAxisX);
    behaviorDeviceSeries->attachAxis(behaviorAxisY);
    behaviorBarView = createChartView(behaviorChart);

    scatterSeries = new QScatterSeries();
    scatterSeries->setName(QStringLiteral("采样点"));
    scatterSeries->setColor(QColor(kBlue));
    scatterSeries->setBorderColor(QColor("#ffffff"));
    scatterSeries->setMarkerSize(13);
    QChart *scatterChart = createBaseChart(QStringLiteral("偏离-专注关联"));
    scatterChart->addSeries(scatterSeries);
    scatterAxisX = new QValueAxis();
    scatterAxisY = new QValueAxis();
    scatterAxisX->setRange(0, 100);
    scatterAxisY->setRange(0, 100);
    scatterAxisX->setTitleText(QStringLiteral("偏离强度"));
    scatterAxisY->setTitleText(QStringLiteral("专注得分"));
    scatterAxisX->setGridLineColor(QColor("#edf2f0"));
    scatterAxisY->setGridLineColor(QColor("#edf2f0"));
    scatterChart->addAxis(scatterAxisX, Qt::AlignBottom);
    scatterChart->addAxis(scatterAxisY, Qt::AlignLeft);
    scatterSeries->attachAxis(scatterAxisX);
    scatterSeries->attachAxis(scatterAxisY);
    scatterChartView = createChartView(scatterChart);

    QHBoxLayout *charts = new QHBoxLayout();
    charts->setSpacing(tinyMode ? 10 : 18);
    charts->addWidget(createPanel(QStringLiteral("行为连续谱"), behaviorBarView), 6);
    charts->addWidget(createPanel(QStringLiteral("关联散点"), scatterChartView), 4);
    layout->addLayout(charts, 5);

    return page;
}

QWidget *MainWindow::createHeatmapPage()
{
    QWidget *page = new QWidget(pageStack);
    QGridLayout *layout = new QGridLayout(page);
    layout->setContentsMargins(0, 0, 0, 0);
    layout->setSpacing(tinyMode ? 10 : 18);

    heatmapWidget = new HeatMapWidget(page);
    radarWidget = new RadarWidget(page);

    QLabel *narrative = new QLabel(
        QStringLiteral("空间模型将座位热力、课堂秩序与设备干扰合成一张“注意力地形图”。"
                       "中心区、边缘区和设备高发区会随实时数据轻微重排，方便老师快速判断干预位置。"),
        page);
    narrative->setWordWrap(true);
    narrative->setStyleSheet(
        "QLabel { color: #5f6f6b; font-size: 15px; line-height: 150%; }"
    );
    QWidget *textPanel = createPanel(QStringLiteral("空间解读"), narrative);
    layout->addWidget(createPanel(QStringLiteral("座位热力地图"), heatmapWidget), 0, 0, 2, 3);
    layout->addWidget(createPanel(QStringLiteral("课堂状态雷达"), radarWidget), 0, 3);
    layout->addWidget(textPanel, 1, 3);
    layout->setColumnStretch(0, 2);
    layout->setColumnStretch(1, 2);
    layout->setColumnStretch(2, 2);
    layout->setColumnStretch(3, 3);
    layout->setRowStretch(0, 3);
    layout->setRowStretch(1, 2);
    return page;
}

QWidget *MainWindow::createWarningPage()
{
    QWidget *page = new QWidget(pageStack);
    QHBoxLayout *layout = new QHBoxLayout(page);
    layout->setContentsMargins(0, 0, 0, 0);
    layout->setSpacing(tinyMode ? 10 : 18);

    riskLineSeries = new QLineSeries();
    riskLineSeries->setName(QStringLiteral("干扰风险"));
    QPen riskPen(QColor(kRed), 3);
    riskPen.setCapStyle(Qt::RoundCap);
    riskLineSeries->setPen(riskPen);
    QChart *riskChart = createBaseChart(QStringLiteral("干扰风险趋势"));
    riskChart->addSeries(riskLineSeries);
    riskAxisX = new QValueAxis();
    riskAxisY = new QValueAxis();
    riskAxisX->setLabelsVisible(false);
    riskAxisX->setRange(0, kHistoryLimit - 1);
    riskAxisY->setRange(0, 100);
    riskAxisY->setGridLineColor(QColor("#edf2f0"));
    riskChart->addAxis(riskAxisX, Qt::AlignBottom);
    riskChart->addAxis(riskAxisY, Qt::AlignLeft);
    riskLineSeries->attachAxis(riskAxisX);
    riskLineSeries->attachAxis(riskAxisY);
    riskChartView = createChartView(riskChart);

    QWidget *eventList = new QWidget(page);
    QVBoxLayout *events = new QVBoxLayout(eventList);
    events->setContentsMargins(0, 0, 0, 0);
    events->setSpacing(tinyMode ? 8 : 12);
    eventLabels.clear();
    eventRows = QStringList() << QStringLiteral("等待课堂数据接入，事件流将随识别结果实时更新");
    for (int i = 0; i < 4; ++i) {
        QLabel *item = new QLabel(i == 0 ? eventRows.first() : QStringLiteral(""), eventList);
        item->setObjectName("eventItem");
        item->setWordWrap(true);
        item->setStyleSheet(
            "QLabel { background: #f6faf8; color: #40504c; border: 1px solid #e3ece8; "
            "border-radius: 8px; padding: 14px; font-size: 14px; }"
        );
        eventLabels.append(item);
        events->addWidget(item);
    }
    events->addStretch();

    layout->addWidget(createPanel(QStringLiteral("预警曲线"), riskChartView), 7);
    layout->addWidget(createPanel(QStringLiteral("课堂事件流"), eventList), 4);
    return page;
}

QWidget *MainWindow::createSettingsPage()
{
    QWidget *page = new QWidget(pageStack);
    QVBoxLayout *layout = new QVBoxLayout(page);
    layout->setContentsMargins(0, 0, 0, 0);
    layout->setSpacing(tinyMode ? 10 : 18);

    QWidget *settings = new QWidget(page);
    settings->setMinimumHeight(tinyMode ? 220 : 270);
    settings->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Minimum);
    QVBoxLayout *settingsLayout = new QVBoxLayout(settings);
    settingsLayout->setContentsMargins(4, 2, 4, 2);
    settingsLayout->setSpacing(tinyMode ? 10 : 14);

    auto editorStyle = QStringLiteral(
        "QLineEdit, QComboBox { background: #f6faf8; color: #17211f; border: 1px solid #dce7e3; "
        "border-radius: 8px; padding: 9px 12px; font-size: 14px; font-weight: 700; }"
        "QLineEdit:focus, QComboBox:focus { border-color: #1fb58f; background: #ffffff; }"
    );

    auto makeRow = [&](const QString &title, const QString &hint, QWidget *editor) -> QWidget* {
        QFrame *row = new QFrame(settings);
        row->setObjectName("settingRow");
        row->setMinimumHeight(tinyMode ? 66 : 76);
        row->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Minimum);
        row->setStyleSheet(
            "QFrame#settingRow { background: #f8fbf9; border: 1px solid #e3ece8; border-radius: 8px; }"
        );
        QHBoxLayout *rowLayout = new QHBoxLayout(row);
        rowLayout->setContentsMargins(tinyMode ? 10 : 14, tinyMode ? 10 : 14,
                                      tinyMode ? 10 : 14, tinyMode ? 10 : 14);
        rowLayout->setSpacing(14);

        QVBoxLayout *copy = new QVBoxLayout();
        QLabel *titleLabel = new QLabel(title, row);
        QLabel *hintLabel = new QLabel(hint, row);
        titleLabel->setStyleSheet("QLabel { color: #17211f; font-size: 15px; font-weight: 900; }");
        hintLabel->setStyleSheet("QLabel { color: #7b8985; font-size: 12px; }");
        copy->addWidget(titleLabel);
        copy->addWidget(hintLabel);
        rowLayout->addLayout(copy, 2);
        rowLayout->addWidget(editor, 3);
        return row;
    };

    QLineEdit *deviceEdit = new QLineEdit(QStringLiteral("elf2-classroom-01"), settings);
    deviceEdit->setStyleSheet(editorStyle);
    settingsLayout->addWidget(makeRow(QStringLiteral("设备编号"),
                                      QStringLiteral("用于区分不同课堂终端"),
                                      deviceEdit));

    QComboBox *cameraBox = new QComboBox(settings);
    cameraBox->addItems(QStringList()
                        << QStringLiteral("课堂摄像头 / 640x480 / 实时流")
                        << QStringLiteral("本地视频文件..."));
    cameraBox->setStyleSheet(editorStyle);
    connect(cameraBox, QOverload<int>::of(&QComboBox::currentIndexChanged), [this, cameraBox](int index) {
        auto restartIfRunning = [this]() {
            if (pythonProcess && pythonProcess->state() == QProcess::Running) {
                onStopClicked();
                QTimer::singleShot(180, this, &MainWindow::onStartClicked);
            }
        };
        if (index == 0) {
            selectedVideoSource = QStringLiteral("11");
            cameraBox->setToolTip(QString());
            cameraBox->setItemText(1, QStringLiteral("本地视频文件..."));
            restartIfRunning();
            return;
        }

        const QString filePath = QFileDialog::getOpenFileName(
            this,
            QStringLiteral("选择本地视频文件"),
            QDir::homePath(),
            QStringLiteral("视频文件 (*.mp4 *.avi *.mov *.mkv *.m4v);;所有文件 (*)"));
        if (filePath.isEmpty()) {
            cameraBox->blockSignals(true);
            cameraBox->setCurrentIndex(0);
            cameraBox->blockSignals(false);
            selectedVideoSource = QStringLiteral("11");
            cameraBox->setItemText(1, QStringLiteral("本地视频文件..."));
            cameraBox->setToolTip(QString());
            return;
        }

        selectedVideoSource = filePath;
        cameraBox->setItemText(1, QStringLiteral("本地视频文件 / %1").arg(QFileInfo(filePath).fileName()));
        cameraBox->setToolTip(filePath);
        restartIfRunning();
    });
    settingsLayout->addWidget(makeRow(QStringLiteral("摄像头源"),
                                      QStringLiteral("选择实时画面的输入来源"),
                                      cameraBox));

    QWidget *themeEditor = new QWidget(settings);
    QHBoxLayout *themeLayout = new QHBoxLayout(themeEditor);
    themeLayout->setContentsMargins(0, 0, 0, 0);
    themeLayout->setSpacing(8);

    auto applyTheme = [this](const QString &key) {
        const bool night = key == QStringLiteral("night");
        const bool coral = key == QStringLiteral("coral");
        setProperty("themeKey", key);
        const QString bg = night ? QStringLiteral("#101615")
                         : (coral ? QStringLiteral("#f7ece8") : QStringLiteral("#edf4f0"));
        const QString side = night ? QStringLiteral("#0b1110")
                           : (coral ? QStringLiteral("#fff7f3") : QStringLiteral("#f8fbf9"));
        const QString border = night ? QStringLiteral("#22302d")
                             : (coral ? QStringLiteral("#ecd2c8") : QStringLiteral("#dce7e3"));
        const QString panel = night ? QStringLiteral("#16211f")
                            : (coral ? QStringLiteral("#fffaf7") : QStringLiteral("#ffffff"));
        const QString rowBg = night ? QStringLiteral("#121c1a")
                            : (coral ? QStringLiteral("#fff1eb") : QStringLiteral("#f8fbf9"));
        const QString inputBg = night ? QStringLiteral("#0f1715")
                              : (coral ? QStringLiteral("#fff7f3") : QStringLiteral("#f6faf8"));
        const QString ink = night ? QStringLiteral("#eaf6f1") : QStringLiteral("#17211f");
        const QString muted = night ? QStringLiteral("#9fb3ad") : QStringLiteral("#6a7874");
        setStyleSheet(QString(
            "QMainWindow { background: %1; }"
            "QLabel { color: %2; font-family: 'Microsoft YaHei'; }"
            "QPushButton { font-family: 'Microsoft YaHei'; }"
            "QToolTip { background: #17211f; color: white; border: 0; padding: 6px; }"
        ).arg(bg, ink));
        if (sideBar) {
            sideBar->setStyleSheet(QString("QWidget { background: %1; border-right: 1px solid %2; }").arg(side, border));
        }
        if (engineStateLabel) engineStateLabel->setStyleSheet(themedPillStyleForText(this, engineStateLabel->text()));
        if (streamStateLabel) streamStateLabel->setStyleSheet(themedPillStyleForText(this, streamStateLabel->text()));
        if (lastUpdateLabel) lastUpdateLabel->setStyleSheet(themedPillStyle(this, QStringLiteral("neutral")));
        if (QLabel *logo = findChild<QLabel*>(QStringLiteral("brandLogo"))) {
            logo->setStyleSheet(QString(
                "QLabel#brandLogo { background: %1; color: %2; border: 1px solid %3; border-radius: 18px; "
                "font-size: %4px; font-weight: 900; line-height: 26px; }"
            ).arg(night ? QStringLiteral("#13211e") : QStringLiteral("#17211f"),
                  night ? QStringLiteral("#dffff7") : QStringLiteral("#f4fbf8"),
                  night ? QStringLiteral("#29463f") : QStringLiteral("#17211f"),
                  QString::number(tinyMode ? 17 : (compactMode ? 19 : 22))));
        }
        if (QPushButton *reset = findChild<QPushButton*>(QStringLiteral("resetButton"))) {
            reset->setStyleSheet(QString(
                "QPushButton#resetButton { color: %1; background: %2; border: 1px solid %3; "
                "border-radius: 9px; font-size: 12px; font-weight: 800; }"
                "QPushButton#resetButton:hover { background: %4; color: %5; border-color: %6; }"
            ).arg(night ? QStringLiteral("#d7e8e1") : muted,
                  night ? QStringLiteral("#17231f") : inputBg,
                  border,
                  night ? QStringLiteral("#21352f") : (coral ? QStringLiteral("#ffe8df") : QStringLiteral("#e8f7f1")),
                  night ? QStringLiteral("#eaf6f1") : QStringLiteral("#147b62"),
                  night ? QStringLiteral("#3b5a51") : QStringLiteral("#bfe5d8")));
        }
        if (btnVideoFullScreen) {
            btnVideoFullScreen->setStyleSheet(QString(
                "QPushButton#videoFullScreenButton { background: %1; color: %2; border: 1px solid %3; "
                "border-radius: 8px; padding: 0 12px; font-size: 12px; font-weight: 700; }"
                "QPushButton#videoFullScreenButton:hover { background: %4; color: %5; border-color: %4; }"
            ).arg(night ? QStringLiteral("#17231f") : inputBg,
                  night ? QStringLiteral("#d7e8e1") : QStringLiteral("#40504c"),
                  border,
                  night ? QStringLiteral("#1fb58f") : QStringLiteral("#17211f"),
                  QStringLiteral("#ffffff")));
        }
        for (QToolButton *button : navButtons) {
            if (!button) continue;
            button->setStyleSheet(QString(
                "QToolButton { background: transparent; color: %1; border: 0; "
                "border-radius: 10px; padding: 6px 4px; font-size: %2px; font-weight: 700; }"
                "QToolButton:hover { background: %3; color: %4; }"
                "QToolButton:checked { background: %5; color: #ffffff; }"
            ).arg(night ? QStringLiteral("#a8bbb5") : QStringLiteral("#65736f"),
                  QString::number(tinyMode ? 10 : 12),
                  night ? QStringLiteral("#16211f") : QStringLiteral("#edf4f0"),
                  night ? QStringLiteral("#eaf6f1") : QStringLiteral("#17211f"),
                  night ? QStringLiteral("#1f8f74") : QStringLiteral("#17211f")));
        }
        if (videoLabel && lastVideoPixmap.isNull()) {
            videoLabel->setStyleSheet(videoPlaceholderStyle(this));
        }
        for (QFrame *frame : findChildren<QFrame*>()) {
            if (frame->objectName() == "panel" || frame->objectName() == "metricCard" || frame->objectName() == "videoPanel") {
                frame->setStyleSheet(QString(
                    "QFrame#%1 { background: %2; border: 1px solid %3; border-radius: 8px; }"
                ).arg(frame->objectName(), panel, border));
            } else if (frame->objectName() == "settingRow") {
                frame->setStyleSheet(QString(
                    "QFrame#settingRow { background: %1; border: 1px solid %2; border-radius: 8px; }"
                ).arg(rowBg, border));
            }
        }
        const QString editor = QString(
            "QLineEdit, QComboBox { background: %1; color: %2; border: 1px solid %3; "
            "border-radius: 8px; padding: 9px 12px; font-size: 14px; font-weight: 700; }"
            "QLineEdit:focus, QComboBox:focus { border-color: #1fb58f; background: %4; }"
        ).arg(inputBg, ink, border, night ? QStringLiteral("#13211e") : QStringLiteral("#ffffff"));
        for (QLineEdit *edit : findChildren<QLineEdit*>()) {
            edit->setStyleSheet(editor);
        }
        for (QComboBox *box : findChildren<QComboBox*>()) {
            box->setStyleSheet(editor);
        }
        for (QLabel *label : findChildren<QLabel*>()) {
            if (label->text().isEmpty()) continue;
            const QString labelName = label->objectName();
            if (labelName == QStringLiteral("brandLogo") || labelName == QStringLiteral("eventItem")) continue;
            QString style = label->styleSheet();
            if (style.isEmpty()) continue;
            if (night) {
                style.replace("#17211f", ink);
                style.replace("#40504c", ink);
                style.replace("#5f6f6b", muted);
                style.replace("#6a7874", muted);
                style.replace("#7b8985", muted);
                style.replace("#8a9894", muted);
            } else {
                style.replace("#eaf6f1", "#17211f");
                style.replace("#9fb3ad", "#6a7874");
            }
            label->setStyleSheet(style);
        }
        const QString eventBg = night ? QStringLiteral("#121c1a")
                              : (coral ? QStringLiteral("#fff7f3") : QStringLiteral("#f6faf8"));
        const QString eventFg = night ? QStringLiteral("#dcece6") : QStringLiteral("#40504c");
        for (QLabel *item : eventLabels) {
            if (!item) continue;
            item->setStyleSheet(QString(
                "QLabel#eventItem { background: %1; color: %2; border: 1px solid %3; "
                "border-radius: 8px; padding: 14px; font-size: 14px; }"
            ).arg(eventBg, eventFg, border));
        }
        for (QChartView *view : findChildren<QChartView*>()) {
            if (!view || !view->chart()) continue;
            view->setStyleSheet(QString("QChartView { background: %1; border: 0; }").arg(night ? QStringLiteral("#121c1a") : panel));
            view->setBackgroundBrush(QBrush(QColor(night ? "#121c1a" : panel)));
            view->chart()->setBackgroundVisible(true);
            view->chart()->setBackgroundBrush(QBrush(QColor(night ? "#121c1a" : panel)));
            view->chart()->setPlotAreaBackgroundVisible(true);
            view->chart()->setPlotAreaBackgroundBrush(QBrush(QColor(night ? "#0f1715" : panel)));
            view->chart()->setTitleBrush(QBrush(QColor(ink)));
            view->chart()->legend()->setLabelColor(QColor(muted));
            for (QAbstractAxis *axis : view->chart()->axes()) {
                axis->setLabelsBrush(QBrush(QColor(muted)));
                axis->setTitleBrush(QBrush(QColor(muted)));
                if (QValueAxis *valueAxis = qobject_cast<QValueAxis*>(axis)) {
                    valueAxis->setGridLineColor(QColor(night ? "#2b3d39" : "#edf2f0"));
                }
            }
        }
        if (heatmapWidget) heatmapWidget->update();
        if (radarWidget) radarWidget->update();
        for (QPushButton *button : findChildren<QPushButton*>()) {
            if (button->objectName() != "themeButton") continue;
            const QString swatch = button->property("themeSwatch").toString();
            button->setStyleSheet(QString(
                "QPushButton { background: %1; color: %2; border: 1px solid %3; "
                "border-radius: 8px; padding: 0 12px; font-size: 13px; font-weight: 900; }"
                "QPushButton:hover { border-color: #1fb58f; }"
            ).arg(night ? QStringLiteral("#17231f") : swatch, ink, border));
        }
    };

    auto makeThemeButton = [&](const QString &text, const QString &key, const QString &swatch) {
        QPushButton *button = new QPushButton(text, themeEditor);
        button->setObjectName("themeButton");
        button->setProperty("themeSwatch", swatch);
        button->setCursor(Qt::PointingHandCursor);
        button->setMinimumHeight(tinyMode ? 34 : 40);
        button->setStyleSheet(QString(
            "QPushButton { background: %1; color: #17211f; border: 1px solid #dce7e3; "
            "border-radius: 8px; padding: 0 12px; font-size: 13px; font-weight: 900; }"
            "QPushButton:hover { border-color: #1fb58f; }"
        ).arg(swatch));
        connect(button, &QPushButton::clicked, [applyTheme, key]() { applyTheme(key); });
        themeLayout->addWidget(button);
    };
    makeThemeButton(QStringLiteral("清透绿"), QStringLiteral("green"), QStringLiteral("#e8f7f1"));
    makeThemeButton(QStringLiteral("珊瑚霞"), QStringLiteral("coral"), QStringLiteral("#ffe8df"));
    makeThemeButton(QStringLiteral("夜间模式"), QStringLiteral("night"), QStringLiteral("#d9e3df"));

    settingsLayout->addWidget(makeRow(QStringLiteral("视觉主题"),
                                      QStringLiteral("新增珊瑚霞与夜间模式"),
                                      themeEditor));
    layout->addStretch();
    layout->addWidget(createPanel(QStringLiteral("设置"), settings));
    layout->addStretch();
    return page;
}

QWidget *MainWindow::createPanel(const QString &title, QWidget *content)
{
    QFrame *panel = new QFrame();
    panel->setObjectName("panel");
    panel->setStyleSheet(
        "QFrame#panel { background: #ffffff; border: 1px solid #dfe9e5; border-radius: 8px; }"
    );
    addSoftShadow(panel);
    QVBoxLayout *layout = new QVBoxLayout(panel);
    const int m = tinyMode ? 10 : 16;
    layout->setContentsMargins(m, tinyMode ? 9 : 14, m, tinyMode ? 10 : 16);
    layout->setSpacing(tinyMode ? 6 : 10);

    QLabel *label = new QLabel(title, panel);
    label->setStyleSheet(QString("QLabel { color: #17211f; font-size: %1px; font-weight: 800; }")
                         .arg(tinyMode ? 13 : 16));
    layout->addWidget(label);

    if (content) {
        content->setParent(panel);
        layout->addWidget(content, 1);
    }
    return panel;
}

QWidget *MainWindow::createMetricCard(const QString &title, const QString &value, const QString &subText, const QString &accent)
{
    QFrame *card = new QFrame();
    card->setObjectName("metricCard");
    card->setStyleSheet(
        "QFrame#metricCard { background: #ffffff; border: 1px solid #dfe9e5; border-radius: 8px; }"
    );
    addSoftShadow(card, 22, 6);
    QVBoxLayout *layout = new QVBoxLayout(card);
    layout->setContentsMargins(tinyMode ? 10 : 16, tinyMode ? 9 : 14,
                               tinyMode ? 10 : 16, tinyMode ? 9 : 14);
    layout->setSpacing(tinyMode ? 3 : 6);

    QHBoxLayout *titleLine = new QHBoxLayout();
    QLabel *dot = new QLabel(card);
    dot->setFixedSize(8, 8);
    dot->setStyleSheet(QString("QLabel { background: %1; border-radius: 4px; }").arg(accent));
    QLabel *titleLabel = new QLabel(title, card);
    titleLabel->setStyleSheet(QString("QLabel { color: #6a7874; font-size: %1px; font-weight: 700; }")
                              .arg(tinyMode ? 11 : 13));
    titleLine->addWidget(dot);
    titleLine->addWidget(titleLabel);
    titleLine->addStretch();

    QLabel *valueLabel = new QLabel(value, card);
    valueLabel->setObjectName("metricValue");
    valueLabel->setStyleSheet(QString("QLabel { color: #17211f; font-size: %1px; font-weight: 900; }")
                              .arg(tinyMode ? 22 : 30));
    QLabel *subLabel = new QLabel(subText, card);
    subLabel->setWordWrap(true);
    subLabel->setStyleSheet(QString("QLabel { color: #8a9894; font-size: %1px; }")
                            .arg(tinyMode ? 10 : 12));

    layout->addLayout(titleLine);
    layout->addWidget(valueLabel);
    layout->addWidget(subLabel);
    return card;
}

QLabel *MainWindow::metricValueLabel(QWidget *card) const
{
    return card ? card->findChild<QLabel*>("metricValue") : nullptr;
}

QChartView *MainWindow::createChartView(QChart *chart) const
{
    QChartView *view = new QChartView(chart);
    view->setRenderHint(QPainter::Antialiasing, true);
    view->setMinimumSize(0, 0);
    view->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Expanding);
    view->setStyleSheet("QChartView { background: transparent; border: 0; }");
    return view;
}

QChart *MainWindow::createBaseChart(const QString &title) const
{
    QChart *chart = new QChart();
    chart->setTitle(title);
    chart->setTitleFont(QFont("Microsoft YaHei", 10, QFont::DemiBold));
    chart->setTitleBrush(QBrush(QColor(kInk)));
    chart->setBackgroundVisible(false);
    chart->setPlotAreaBackgroundVisible(false);
    chart->setMargins(QMargins(0, 0, 0, 0));
    chart->setAnimationOptions(QChart::NoAnimation);
    chart->legend()->setLabelColor(QColor(kMuted));
    chart->legend()->setFont(QFont("Microsoft YaHei", 8));
    return chart;
}

void MainWindow::setupChart()
{
    currentBehaviors.clear();
    currentTotal = 0;
}

void MainWindow::resetAnalyticsState()
{
    currentBehaviors.clear();
    focusHistory.clear();
    totalHistory.clear();
    distractedHistory.clear();
    fatigueHistory.clear();
    phoneHistory.clear();
    currentTotal = 0;
    sampleIndex = 0;
    frameCounter = 0;
    lastEventSignature.clear();
    eventRows = QStringList() << QStringLiteral("等待课堂数据接入，事件流将随识别结果实时更新");
    for (int i = 0; i < eventLabels.size(); ++i) {
        eventLabels[i]->setText(i < eventRows.size() ? eventRows[i] : QStringLiteral(""));
    }
    updateVisuals();
}

void MainWindow::seedDemoData()
{
    currentBehaviors.clear();
    currentBehaviors.insert("listening", 31);
    currentBehaviors.insert("reading", 6);
    currentBehaviors.insert("writing", 8);
    currentBehaviors.insert("eating_drinking", 2);
    currentBehaviors.insert("using_phone", 1);
    currentBehaviors.insert("using_tablet", 1);
    currentBehaviors.insert("using_laptop", 0);
    currentTotal = 49;

    focusHistory = QVector<int>() << 72 << 76 << 78 << 74 << 81 << 84 << 82 << 86 << 83 << 88 << 85 << 89;
    totalHistory = QVector<int>() << 48 << 49 << 49 << 49 << 50 << 50 << 50 << 49 << 49 << 50 << 49 << 49;
    distractedHistory = QVector<int>() << 3 << 2 << 2 << 4 << 1 << 1 << 2 << 1 << 2 << 1 << 1 << 2;
    fatigueHistory = QVector<int>() << 0 << 0 << 0 << 0 << 0 << 0 << 0 << 0 << 0 << 0 << 0 << 0;
    phoneHistory = QVector<int>() << 2 << 1 << 1 << 2 << 1 << 1 << 1 << 0 << 1 << 0 << 1 << 1;
    sampleIndex = focusHistory.size();
    updateVisuals();
}

void MainWindow::refreshNav()
{
    for (int i = 0; i < navButtons.size(); ++i) {
        navButtons[i]->setChecked(i == currentPageIndex);
    }
}

void MainWindow::setActivePage(int index)
{
    if (!pageStack || index < 0 || index >= pageStack->count()) return;
    currentPageIndex = index;
    pageStack->setCurrentIndex(index);
    const QStringList titles = QStringList()
        << QStringLiteral("实况中枢")
        << QStringLiteral("行为洞察")
        << QStringLiteral("空间热力")
        << QStringLiteral("趋势预警")
        << QStringLiteral("系统设置");
    if (index < titles.size()) {
        pageTitleLabel->setText(titles[index]);
    }
    refreshNav();
    QTimer::singleShot(20, this, &MainWindow::playEntranceAnimation);
}

void MainWindow::refreshClock()
{
    if (lastUpdateLabel) {
        lastUpdateLabel->setText(QStringLiteral("时间 %1").arg(
            QDateTime::currentDateTime().toString(QStringLiteral("HH:mm:ss"))));
    }
}

void MainWindow::onStartClicked()
{
    if (isPaused) {
        isPaused = false;
        engineStateLabel->setText(QStringLiteral("引擎运行中"));
        engineStateLabel->setStyleSheet(themedPillStyle(this, QStringLiteral("success")));
        streamStateLabel->setText(QStringLiteral("视频流在线"));
        streamStateLabel->setStyleSheet(themedPillStyle(this, QStringLiteral("success")));
        if (btnPause) {
            btnPause->setText(QStringLiteral("暂停画面"));
        }
        return;
    }
    if (pythonProcess->state() == QProcess::Running) return;

    QString workDir = QCoreApplication::applicationDirPath();
    if (!QFileInfo::exists(QDir(workDir).filePath(QStringLiteral("behavior_detection_ov13855.py")))) {
        workDir = QDir::currentPath();
    }
    if (!QFileInfo::exists(QDir(workDir).filePath(QStringLiteral("behavior_detection_ov13855.py")))) {
        workDir = QStringLiteral("/home/elf/Desktop/ourwork/");
    }
    pythonProcess->setWorkingDirectory(workDir);
    const QString scriptPath = QDir(workDir).filePath(QStringLiteral("behavior_detection_ov13855.py"));

    QProcessEnvironment env = QProcessEnvironment::systemEnvironment();
    env.insert("QT_QPA_PLATFORM", "offscreen");
    pythonProcess->setProcessEnvironment(env);

    QStringList args;
    args << "-u"
         << scriptPath
         << "--model" << "yolov8_split_int8_rk3588.rknn"
         << "--backend" << "rknn"
         << "--source" << selectedVideoSource
         << "--imgsz" << "640"
         << "--conf" << "0.20"
         << "--iou" << "0.45"
         << "--input-mode" << "auto"
         << "--input-layout" << "nhwc"
         << "--camera-width" << "640"
         << "--camera-height" << "480"
         << "--camera-fps" << "30"
         << "--report-url" << "http://10.135.79.168:8080/api/realtime/report"
         << "--report-interval" << "3"
         << "--device-id" << "elf2-classroom-01"
         << "--class-id" << "demo-001"
         << "--report-timeout" << "0.5"
         << "--report-retries" << "0"
         << "--report-backoff" << "0.3"
         << "--report-spool" << "runs/realtime/spool.ndjson"
         << "--report-spool-max" << "5000"
         << "--report-flush-every" << "3"
         << "--no-show";

#ifdef Q_OS_WIN
    pythonProcess->start("python", args);
#else
    pythonProcess->start("/usr/bin/python3", args);
#endif

    videoLabel->setStyleSheet(videoPlaceholderStyle(this));
    videoLabel->setText(QStringLiteral("正在连接课堂画面，请稍候..."));
    engineStateLabel->setText(QStringLiteral("引擎启动中"));
    engineStateLabel->setStyleSheet(themedPillStyle(this, QStringLiteral("warning")));
}

void MainWindow::onPauseClicked()
{
    isPaused = !isPaused;
    if (isPaused) {
        engineStateLabel->setText(QStringLiteral("画面已暂停"));
        engineStateLabel->setStyleSheet(themedPillStyle(this, QStringLiteral("warning")));
        streamStateLabel->setText(QStringLiteral("数据已冻结"));
        streamStateLabel->setStyleSheet(themedPillStyle(this, QStringLiteral("warning")));
        btnPause->setText(QStringLiteral("继续观察"));
    } else {
        engineStateLabel->setText(QStringLiteral("引擎运行中"));
        engineStateLabel->setStyleSheet(themedPillStyle(this, QStringLiteral("success")));
        streamStateLabel->setText(QStringLiteral("视频流在线"));
        streamStateLabel->setStyleSheet(themedPillStyle(this, QStringLiteral("success")));
        btnPause->setText(QStringLiteral("暂停画面"));
    }
}

void MainWindow::onStopClicked()
{
    isPaused = false;
    if (pythonProcess && pythonProcess->state() == QProcess::Running) {
        pythonProcess->kill();
        pythonProcess->waitForFinished(300);
    }

#ifndef Q_OS_WIN
    system("pkill -9 -f behavior_detection_ov13855.py");
#endif

    if (udpSocket) {
        while (udpSocket->hasPendingDatagrams()) {
            udpSocket->receiveDatagram();
        }
    }

    videoLabel->clear();
    videoLabel->setPixmap(QPixmap());
    lastVideoPixmap = QPixmap();
    videoLabel->setStyleSheet(videoPlaceholderStyle(this));
    videoLabel->setText(QStringLiteral("分析已结束，点击“开始分析”重新接入课堂画面"));
    if (btnPause) {
        btnPause->setText(QStringLiteral("暂停画面"));
    }
    closeVideoFullScreen();
    resetAnalyticsState();
    engineStateLabel->setText(QStringLiteral("引擎待命"));
    engineStateLabel->setStyleSheet(themedPillStyle(this, QStringLiteral("neutral")));
    streamStateLabel->setText(QStringLiteral("视频已停止"));
    streamStateLabel->setStyleSheet(themedPillStyle(this, QStringLiteral("neutral")));
}

void MainWindow::closeEvent(QCloseEvent *event)
{
    onStopClicked();
    event->accept();
}

void MainWindow::processPendingDatagrams()
{
    QString latestData;
    QString latestFramePath;

    while (udpSocket->hasPendingDatagrams()) {
        QByteArray datagram;
        datagram.resize(int(udpSocket->pendingDatagramSize()));
        udpSocket->readDatagram(datagram.data(), datagram.size());
        if (isPaused) {
            continue;
        }

        QString msg = QString::fromUtf8(datagram).trimmed();
        if (msg.startsWith("DATA:")) {
            latestData = msg.mid(5);
        } else if (msg.startsWith("FILE:")) {
            latestFramePath = msg.mid(5).trimmed();
        }
    }

    if (!latestData.isEmpty()) {
        qDebug() << ">>> [Qt 收到数据]:" << latestData;
        updateCharts(latestData);
    }

    if (!latestFramePath.isEmpty()) {
        QImage frameImage;
        if (frameImage.load(latestFramePath)) {
            QPixmap pixmap = QPixmap::fromImage(frameImage);
            lastVideoPixmap = pixmap;
            videoLabel->setScaledContents(true);
            videoLabel->setPixmap(pixmap);
            updateFullScreenFrame();
            streamStateLabel->setText(QStringLiteral("视频流在线"));
            streamStateLabel->setStyleSheet(themedPillStyle(this, QStringLiteral("success")));

            ++frameCounter;
            if (frameFpsTimer.elapsed() >= 1000) {
                frameCounter = 0;
                frameFpsTimer.restart();
            }
        }
    }
}

void MainWindow::toggleVideoFullScreen()
{
    if (videoFullDialog && videoFullDialog->isVisible()) {
        closeVideoFullScreen();
        return;
    }

    videoFullDialog = new QDialog(this);
    videoFullDialog->setAttribute(Qt::WA_DeleteOnClose, true);
    videoFullDialog->setWindowFlags(Qt::Window | Qt::FramelessWindowHint);
    videoFullDialog->installEventFilter(this);
    videoFullDialog->setStyleSheet(
        "QDialog { background: #0b1110; }"
        "QPushButton { background: rgba(255,255,255,0.92); color: #17211f; border: 0; "
        "border-radius: 8px; padding: 8px 16px; font-size: 14px; font-weight: 800; }"
        "QPushButton:hover { background: #1fb58f; color: white; }"
    );

    QVBoxLayout *layout = new QVBoxLayout(videoFullDialog);
    layout->setContentsMargins(14, 14, 14, 14);
    layout->setSpacing(10);

    QHBoxLayout *header = new QHBoxLayout();
    QLabel *title = new QLabel(QStringLiteral("智眸实时视频"), videoFullDialog);
    title->setStyleSheet("QLabel { color: white; font-size: 18px; font-weight: 800; }");
    QPushButton *exitButton = new QPushButton(QStringLiteral("退出全屏"), videoFullDialog);
    connect(exitButton, &QPushButton::clicked, this, &MainWindow::closeVideoFullScreen);
    header->addWidget(title);
    header->addStretch();
    header->addWidget(exitButton);
    layout->addLayout(header);

    fullVideoLabel = new QLabel(videoFullDialog);
    fullVideoLabel->setAlignment(Qt::AlignCenter);
    fullVideoLabel->setScaledContents(true);
    fullVideoLabel->setStyleSheet("QLabel { background: #ffffff; border-radius: 8px; }");
    layout->addWidget(fullVideoLabel, 1);

    connect(videoFullDialog, &QObject::destroyed, [this](QObject *) {
        videoFullDialog = nullptr;
        fullVideoLabel = nullptr;
        if (btnVideoFullScreen) {
            btnVideoFullScreen->setText(QStringLiteral("全屏查看"));
        }
    });

    updateFullScreenFrame();
    if (btnVideoFullScreen) {
        btnVideoFullScreen->setText(QStringLiteral("退出全屏"));
    }
    videoFullDialog->showFullScreen();
}

void MainWindow::closeVideoFullScreen()
{
    if (videoFullDialog) {
        videoFullDialog->close();
    }
}

void MainWindow::updateFullScreenFrame()
{
    if (fullVideoLabel && !lastVideoPixmap.isNull()) {
        fullVideoLabel->setPixmap(lastVideoPixmap);
    }
}

void MainWindow::pushClassroomEvent(const QString &eventText)
{
    if (eventText.trimmed().isEmpty()) return;
    const QString row = QStringLiteral("%1  %2").arg(
        QDateTime::currentDateTime().toString(QStringLiteral("HH:mm:ss")),
        eventText);
    if (!eventRows.isEmpty() && eventRows.first() == row) return;
    eventRows.prepend(row);
    while (eventRows.size() > 4) {
        eventRows.removeLast();
    }
    for (int i = 0; i < eventLabels.size(); ++i) {
        eventLabels[i]->setText(i < eventRows.size() ? eventRows[i] : QStringLiteral(""));
    }
}

bool MainWindow::eventFilter(QObject *obj, QEvent *event)
{
    if (obj == videoFullDialog && event->type() == QEvent::KeyPress) {
        QKeyEvent *keyEvent = static_cast<QKeyEvent*>(event);
        if (keyEvent->key() == Qt::Key_Escape) {
            closeVideoFullScreen();
            return true;
        }
    }
    const bool donutClicked = donutChartView && (obj == donutChartView || obj == donutChartView->viewport());
    const bool focusClicked = focusTrendView && (obj == focusTrendView || obj == focusTrendView->viewport());
    if ((donutClicked || focusClicked) && event->type() == QEvent::MouseButtonRelease) {
        QMouseEvent *mouseEvent = static_cast<QMouseEvent*>(event);
        if (mouseEvent->button() == Qt::LeftButton) {
            showChartPopup(donutClicked ? donutChartView : focusTrendView,
                           donutClicked ? QStringLiteral("分布矩阵") : QStringLiteral("专注曲线"));
            return true;
        }
    }
    return QMainWindow::eventFilter(obj, event);
}

void MainWindow::showChartPopup(QChartView *sourceView, const QString &title)
{
    if (!sourceView) return;
    if (chartPopupDialog) {
        chartPopupDialog->close();
    }

    chartPopupDialog = new QDialog(this);
    chartPopupDialog->setAttribute(Qt::WA_DeleteOnClose, true);
    chartPopupDialog->resize(qMax(520, qMin(width() - 120, 980)), qMax(360, qMin(height() - 100, 680)));
    chartPopupDialog->setWindowTitle(title);
    chartPopupDialog->setStyleSheet(
        "QDialog { background: #edf4f0; }"
        "QPushButton { background: #17211f; color: white; border: 0; border-radius: 8px; "
        "padding: 8px 16px; font-weight: 800; }"
        "QPushButton:hover { background: #1fb58f; }"
    );
    QDialog *dialogPtr = chartPopupDialog;
    connect(chartPopupDialog, &QObject::destroyed, [this, dialogPtr](QObject *) {
        if (chartPopupDialog == dialogPtr) {
            chartPopupDialog = nullptr;
            popupPieSeries = nullptr;
            popupFocusLineSeries = nullptr;
            popupFocusAxisX = nullptr;
            popupFocusAxisY = nullptr;
            chartPopupKind.clear();
        }
    });

    QVBoxLayout *layout = new QVBoxLayout(chartPopupDialog);
    layout->setContentsMargins(18, 18, 18, 18);
    layout->setSpacing(12);

    QHBoxLayout *header = new QHBoxLayout();
    QLabel *titleLabel = new QLabel(title, chartPopupDialog);
    titleLabel->setStyleSheet("QLabel { color: #17211f; font-size: 20px; font-weight: 900; }");
    QPushButton *closeButton = new QPushButton(QStringLiteral("关闭"), chartPopupDialog);
    connect(closeButton, &QPushButton::clicked, chartPopupDialog, &QDialog::close);
    header->addWidget(titleLabel);
    header->addStretch();
    header->addWidget(closeButton);
    layout->addLayout(header);

    QChart *chart = createBaseChart(title);
    if (sourceView == donutChartView) {
        chartPopupKind = "donut";
        popupPieSeries = new QPieSeries();
        popupPieSeries->setHoleSize(0.5);
        chart->addSeries(popupPieSeries);
        chart->legend()->setVisible(true);
        chart->legend()->setAlignment(Qt::AlignBottom);
    } else {
        chartPopupKind = "focus";
        popupFocusLineSeries = new QLineSeries();
        popupFocusLineSeries->setName(QStringLiteral("专注度"));
        QPen pen(QColor(kGreen), 4);
        pen.setCapStyle(Qt::RoundCap);
        popupFocusLineSeries->setPen(pen);
        chart->addSeries(popupFocusLineSeries);
        popupFocusAxisX = new QValueAxis();
        popupFocusAxisY = new QValueAxis();
        popupFocusAxisX->setLabelsVisible(false);
        popupFocusAxisY->setLabelFormat("%d");
        popupFocusAxisY->setGridLineColor(QColor("#dce7e3"));
        chart->addAxis(popupFocusAxisX, Qt::AlignBottom);
        chart->addAxis(popupFocusAxisY, Qt::AlignLeft);
        popupFocusLineSeries->attachAxis(popupFocusAxisX);
        popupFocusLineSeries->attachAxis(popupFocusAxisY);
        chart->legend()->hide();
    }

    QChartView *popupView = createChartView(chart);
    layout->addWidget(popupView, 1);
    refreshChartPopup();
    chartPopupDialog->show();
}

void MainWindow::refreshChartPopup()
{
    if (!chartPopupDialog) return;
    if (chartPopupKind == "donut" && popupPieSeries) {
        popupPieSeries->clear();
        if (!series) return;
        for (QPieSlice *src : series->slices()) {
            QPieSlice *slice = popupPieSeries->append(src->label(), src->value());
            slice->setColor(src->color());
            slice->setBorderColor(QColor("#ffffff"));
            slice->setBorderWidth(2);
            slice->setLabelVisible(true);
            slice->setLabel(QStringLiteral("%1 %2").arg(src->label()).arg(int(src->value())));
        }
    } else if (chartPopupKind == "focus" && popupFocusLineSeries && popupFocusAxisX && popupFocusAxisY) {
        popupFocusLineSeries->clear();
        for (int i = 0; i < focusHistory.size(); ++i) {
            popupFocusLineSeries->append(i, focusHistory[i]);
        }
        popupFocusAxisX->setRange(0, qMax(kHistoryLimit - 1, focusHistory.size() - 1));
        popupFocusAxisY->setRange(0, 100);
    }
}

int MainWindow::behaviorValue(const QString &key) const
{
    return currentBehaviors.value(key, 0);
}

QString MainWindow::normalizeBehaviorKey(const QString &key) const
{
    QString k = key.trimmed().toLower();
    k.replace("_", " ");
    k.replace("-", " ");
    k.replace("/", " ");
    while (k.contains("  ")) {
        k.replace("  ", " ");
    }

    if (k == "listening" || k == "attentive" || k == "focus" || k == QStringLiteral("听课") || k == QStringLiteral("听讲")) {
        return "listening";
    }
    if (k == "writing" || k == "reading" || k == QStringLiteral("书写") || k == QStringLiteral("阅读")) {
        return k == "reading" || k == QStringLiteral("阅读") ? "reading" : "writing";
    }
    if (k == "using phone" || k == "phone" || k == QStringLiteral("玩手机") || k == QStringLiteral("手机")) {
        return "using_phone";
    }
    if (k == "using laptop" || k == "using laptap" || k == "laptop" || k == "laptap"
        || k == QStringLiteral("用电脑") || k == QStringLiteral("电脑")) {
        return "using_laptop";
    }
    if (k == "using tablet" || k == "tablet" || k == QStringLiteral("用平板") || k == QStringLiteral("平板")) {
        return "using_tablet";
    }
    if (k == "eating drinking" || k == "eating" || k == "drinking" || k == QStringLiteral("吃喝")) {
        return "eating_drinking";
    }
    return k.replace(" ", "_");
}

void MainWindow::updateCharts(QString data)
{
    QString normalized = data.trimmed();
    normalized.replace(";", ",");
    normalized.replace("\n", ",");

    QStringList items = normalized.split(",", Qt::SkipEmptyParts);
    QMap<QString, int> incoming;
    int totalHint = 0;

    for (const QString &item : items) {
        QStringList kv = item.split(":");
        if (kv.size() != 2) continue;
        QString key = kv[0].trimmed();
        int value = kv[1].trimmed().toInt();
        if (key == "total" || key == "count" || key == "people") {
            totalHint = value;
        } else {
            const QString normalizedKey = normalizeBehaviorKey(key);
            incoming[normalizedKey] = incoming.value(normalizedKey, 0) + qMax(0, value);
        }
    }

    if (incoming.isEmpty() && totalHint == 0) return;
    if (!incoming.isEmpty()) {
        currentBehaviors = incoming;
    }

    int listening = behaviorValue("listening") + behaviorValue("writing") + behaviorValue("reading");
    int eating = behaviorValue("eating_drinking");
    int phone = behaviorValue("using_phone") + behaviorValue("using_laptop") + behaviorValue("using_tablet");
    int total = totalHint;
    if (total <= 0) {
        for (auto it = currentBehaviors.constBegin(); it != currentBehaviors.constEnd(); ++it) {
            total += it.value();
        }
    }
    currentTotal = total;
    int focusScore = total > 0 ? qBound(0, listening * 100 / total, 100) : 0;

    updateHistory(focusScore, total, eating, 0, phone);
    updateVisuals();
}

void MainWindow::updateHistory(int focusScore, int total, int distracted, int fatigue, int phone)
{
    focusHistory.append(focusScore);
    totalHistory.append(total);
    distractedHistory.append(distracted);
    fatigueHistory.append(fatigue);
    phoneHistory.append(phone);
    while (focusHistory.size() > kHistoryLimit) focusHistory.remove(0);
    while (totalHistory.size() > kHistoryLimit) totalHistory.remove(0);
    while (distractedHistory.size() > kHistoryLimit) distractedHistory.remove(0);
    while (fatigueHistory.size() > kHistoryLimit) fatigueHistory.remove(0);
    while (phoneHistory.size() > kHistoryLimit) phoneHistory.remove(0);
    ++sampleIndex;
}

void MainWindow::updateVisuals()
{
    int listening = behaviorValue("listening") + behaviorValue("writing") + behaviorValue("reading");
    int eating = behaviorValue("eating_drinking");
    int phone = behaviorValue("using_phone") + behaviorValue("using_laptop") + behaviorValue("using_tablet");
    int offTask = eating + phone;
    int fatigue = 0;
    int behaviorSum = 0;
    for (auto it = currentBehaviors.constBegin(); it != currentBehaviors.constEnd(); ++it) {
        behaviorSum += it.value();
    }
    int total = currentTotal > 0 ? currentTotal : behaviorSum;
    int focusScore = total > 0 ? qBound(0, listening * 100 / total, 100) : 0;
    int riskScore = total > 0 ? qBound(0, 100 - focusScore + eating * 4 + phone * 6, 100) : 0;

    if (totalCountLabel) totalCountLabel->setText(QStringLiteral("%1 人").arg(total));
    if (focusScoreLabel) focusScoreLabel->setText(QStringLiteral("%1%").arg(focusScore));
    if (listeningMetricLabel) listeningMetricLabel->setText(QStringLiteral("%1").arg(listening));
    if (distractedMetricLabel) distractedMetricLabel->setText(QStringLiteral("%1").arg(offTask));
    if (fatigueMetricLabel) fatigueMetricLabel->setText(QStringLiteral("%1").arg(eating));
    if (phoneMetricLabel) phoneMetricLabel->setText(QStringLiteral("%1").arg(phone));
    if (riskValueLabel) riskValueLabel->setText(QStringLiteral("%1").arg(riskScore));
    if (stabilityMetricLabel) stabilityMetricLabel->setText(QStringLiteral("%1%").arg(qBound(0, 100 - riskScore / 2, 100)));

    QString eventText;
    if (phone > 0) {
        eventText = QStringLiteral("检测到 %1 个设备使用行为，建议关注后排或侧翼区域").arg(phone);
    } else if (eating > 0) {
        eventText = QStringLiteral("检测到 %1 个 eating/drinking 行为，可轻量提醒保持课堂节奏").arg(eating);
    } else if (offTask >= 3) {
        eventText = QStringLiteral("偏离行为升高到 %1 次，建议增加提问或巡视").arg(offTask);
    } else if (focusScore >= 80) {
        eventText = QStringLiteral("课堂专注度保持在 %1%，适合推进关键内容").arg(focusScore);
    } else if (total > 0) {
        eventText = QStringLiteral("已接入 %1 个识别目标，课堂状态持续采样中").arg(total);
    }
    const QString eventSignature = QStringLiteral("%1|%2|%3|%4")
        .arg(focusScore / 10)
        .arg(eating)
        .arg(offTask)
        .arg(phone);
    if (!eventText.isEmpty() && eventSignature != lastEventSignature) {
        lastEventSignature = eventSignature;
        pushClassroomEvent(eventText);
    }

    if (trendValueLabel) {
        int delta = 0;
        if (focusHistory.size() >= 2) {
            delta = focusHistory.last() - focusHistory[focusHistory.size() - 2];
        }
        trendValueLabel->setText(QString("%1%2%").arg(delta >= 0 ? "+" : "").arg(delta));
    }
    if (series) {
        series->clear();
        struct SliceData { QString name; int value; QColor color; };
        QVector<SliceData> slices;
        slices << SliceData{QStringLiteral("听讲"), behaviorValue("listening"), QColor(kGreen)}
               << SliceData{QStringLiteral("阅读"), behaviorValue("reading"), QColor("#62c3a7")}
               << SliceData{QStringLiteral("书写"), behaviorValue("writing"), QColor("#8dd7c1")}
               << SliceData{QStringLiteral("饮食"), eating, QColor(kAmber)}
               << SliceData{QStringLiteral("使用设备"), phone, QColor(kBlue)};
        for (const SliceData &item : slices) {
            QPieSlice *slice = series->append(item.name, item.value > 0 ? item.value : 0.001);
            slice->setColor(item.color);
            slice->setLabelVisible(false);
            slice->setBorderColor(QColor("#ffffff"));
            slice->setBorderWidth(2);
        }
    }

    if (focusLineSeries) {
        focusLineSeries->clear();
        for (int i = 0; i < focusHistory.size(); ++i) {
            focusLineSeries->append(i, focusHistory[i]);
        }
        focusAxisX->setRange(0, qMax(kHistoryLimit - 1, focusHistory.size() - 1));
    }

    if (riskLineSeries) {
        riskLineSeries->clear();
        for (int i = 0; i < focusHistory.size(); ++i) {
            int risk = qBound(0, 100 - focusHistory[i] + distractedHistory.value(i, 0) * 4
                              + phoneHistory.value(i, 0) * 6, 100);
            riskLineSeries->append(i, risk);
        }
        riskAxisX->setRange(0, qMax(kHistoryLimit - 1, focusHistory.size() - 1));
    }

    if (behaviorFocusSeries && behaviorEatingSeries && behaviorDeviceSeries
        && behaviorAxisX && behaviorAxisY) {
        int maxY = 10;
        QList<QPointF> focusPoints;
        QList<QPointF> eatingPoints;
        QList<QPointF> devicePoints;
        const int emptySlots = qMax(0, kHistoryLimit - focusHistory.size());
        for (int i = 0; i < kHistoryLimit; ++i) {
            const int src = i - emptySlots;
            int focusCount = 0;
            int eatingCount = 0;
            int phoneCount = 0;
            if (src >= 0 && src < focusHistory.size()) {
                int totalAt = qMax(1, totalHistory.value(src, total));
                focusCount = focusHistory.value(src, 0) * totalAt / 100;
                eatingCount = distractedHistory.value(src, 0);
                phoneCount = phoneHistory.value(src, 0);
            }
            focusPoints.append(QPointF(i, focusCount));
            eatingPoints.append(QPointF(i, eatingCount));
            devicePoints.append(QPointF(i, phoneCount));
            maxY = qMax(maxY, qMax(focusCount, qMax(eatingCount, phoneCount)));
        }
        behaviorFocusSeries->clear();
        behaviorEatingSeries->clear();
        behaviorDeviceSeries->clear();
        for (const QPointF &point : focusPoints) {
            behaviorFocusSeries->append(point);
        }
        for (const QPointF &point : eatingPoints) {
            behaviorEatingSeries->append(point);
        }
        for (const QPointF &point : devicePoints) {
            behaviorDeviceSeries->append(point);
        }
        behaviorAxisX->setRange(0, kHistoryLimit - 1);
        behaviorAxisY->setRange(0, qMax(8, maxY + 2));
    }

    if (scatterSeries) {
        scatterSeries->clear();
        for (int i = 0; i < focusHistory.size(); ++i) {
            int totalAt = qMax(1, totalHistory.value(i, total));
            int distractStrength = qBound(0, distractedHistory.value(i, 0) * 100 / totalAt
                                          + phoneHistory.value(i, 0) * 9, 100);
            scatterSeries->append(distractStrength, focusHistory.value(i, 0));
        }
    }

    if (heatmapWidget) {
        heatmapWidget->setProfile(focusScore, eating, fatigue, phone);
    }
    if (radarWidget) {
        int interaction = qBound(0, 88 - offTask * 5, 100);
        int stability = qBound(0, 96 - riskScore, 100);
        int fatigueControl = qBound(0, 100 - eating * 18, 100);
        int deviceControl = qBound(0, 100 - phone * 18, 100);
        radarWidget->setScores(focusScore, interaction, stability, fatigueControl, deviceControl);
    }
    refreshChartPopup();
}

void MainWindow::keyPressEvent(QKeyEvent *event)
{
    if (event->key() == Qt::Key_Escape) {
        if (videoFullDialog && videoFullDialog->isVisible()) {
            closeVideoFullScreen();
            return;
        }
        close();
        return;
    }
    QMainWindow::keyPressEvent(event);
}
